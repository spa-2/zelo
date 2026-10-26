package com.server.zelo.domain.order.scheduler;

import com.server.zelo.domain.order.service.OrderFacade;
import com.server.zelo.domain.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "zelo.order.expiration.scheduler.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class OrderExpirationScheduler {
    private static final int EXPIRATION_MINUTES = 30;

    private final OrderFacade orderFacade;
    private final OrderService orderService;

    @Scheduled(fixedDelayString = "${zelo.order.expiration.scheduler.fixed-delay:30000}")
    public void cancelExpiredOrders() {
        // 한 번의 실행에서는 모든 주문에 같은 만료 기준 시각을 적용
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(EXPIRATION_MINUTES);

        List<Long> orderIds = orderService.findExpiredOrderIds(cutoff);

        for (Long orderId : orderIds) {
            try {
                // Facade를 통해 주문별 락과 취소 트랜잭션을 적용
                orderFacade.cancelExpiredOrder(orderId, cutoff);
            } catch (RuntimeException exception) {
                // 한 주문의 실패가 나머지 주문의 만료 처리를 막지 않도록 함
                log.error("결제 대기 만료 주문 자동 취소 실패 orderId={}", orderId, exception);
            }
        }
    }
}