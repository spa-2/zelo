package com.server.zelo.domain.delivery.scheduler;

import com.server.zelo.common.monitoring.BusinessMetric;
import com.server.zelo.common.monitoring.BusinessMetrics;
import com.server.zelo.domain.delivery.config.DeliveryProperties;
import com.server.zelo.domain.delivery.entity.DeliveryStatus;
import com.server.zelo.domain.delivery.service.DeliveryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.util.function.IntSupplier;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "zelo.delivery.scheduler.enabled", havingValue = "true",matchIfMissing = true)
public class DeliveryScheduler {

    private final DeliveryService deliveryService;
    private final BusinessMetrics businessMetrics;
    private final DeliveryProperties deliveryProperties;

    // 택배사 연동 전까지 시간이 지나면 배송 상태를 다음 단계로 넘긴다
    @Scheduled(fixedDelayString = "${zelo.delivery.scheduler.fixed-delay}")
    public void progressDeliveries() {
        LocalDateTime now = LocalDateTime.now();
        // 배송 완료를 먼저 처리해 이번 실행에 출고된 배송이 바로 완료되지 않게 한다
        int delivered = progress(DeliveryStatus.DELIVERED,
                () -> deliveryService.completeShippingDeliveries(now.minus(deliveryProperties.completeAfter()), now));
        int shipped = progress(DeliveryStatus.SHIPPING,
                () -> deliveryService.shipReadyDeliveries(now.minus(deliveryProperties.shipAfter()), now));
        if (shipped + delivered > 0) {
            log.info("배송 상태 자동 진행 shipped={}, delivered={}", shipped, delivered);
        }
    }

    // 한 번에 BATCH_SIZE건씩 끊어서 커밋하고, 꽉 차게 가져왔으면 남은 배송이 있으므로 이어서 처리한다
    // 한 단계가 실패해도 다른 단계는 진행되게 하고, 실패 전에 커밋한 건수는 그대로 센다
    private int progress(DeliveryStatus target, IntSupplier batch) {
        int total = 0;
        try {
            int processed;
            do {
                processed = batch.getAsInt();
                total += processed;
            } while (processed == DeliveryService.BATCH_SIZE);
        } catch (RuntimeException e) {
            // 스케줄러 예외는 GlobalExceptionHandler에 닿지 않으므로 여기서 남기고 센다. 남은 배송은 다음 실행에서 다시 처리한다
            log.error("배송 상태 자동 진행 실패 status={}, processed={}", target, total, e);
            businessMetrics.count(BusinessMetric.DELIVERY_SCHEDULER_FAILED, "status", target.name());
        }
        if (total > 0) {
            businessMetrics.count(BusinessMetric.DELIVERY_PROGRESSED, total, "status", target.name());
        }
        return total;
    }
}
