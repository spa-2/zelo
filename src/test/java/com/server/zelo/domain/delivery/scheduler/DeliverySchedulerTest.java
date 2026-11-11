package com.server.zelo.domain.delivery.scheduler;

import com.server.zelo.common.monitoring.BusinessMetrics;
import com.server.zelo.domain.delivery.config.DeliveryProperties;
import com.server.zelo.domain.delivery.service.DeliveryService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class DeliverySchedulerTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @Mock
    private DeliveryService deliveryService;

    @Test
    void 실행하면_배송_완료와_출고를_모두_처리한다() {
        // given
        DeliveryScheduler scheduler = scheduler();

        // when
        scheduler.progressDeliveries();

        // then
        then(deliveryService).should().completeShippingDeliveries(any(), any());
        then(deliveryService).should().shipReadyDeliveries(any(), any());
    }

    @Test
    void 배송_완료가_실패해도_출고는_처리하고_실패를_센다() {
        // given
        given(deliveryService.completeShippingDeliveries(any(), any())).willThrow(new IllegalStateException("db"));
        given(deliveryService.shipReadyDeliveries(any(), any())).willReturn(3);

        // when
        scheduler().progressDeliveries();

        // then
        assertThat(meterRegistry.get("zelo.delivery.scheduler.failed").tag("status", "DELIVERED").counter().count())
                .isEqualTo(1);
        assertThat(meterRegistry.get("zelo.delivery.progressed").tag("status", "SHIPPING").counter().count())
                .isEqualTo(3);
    }

    private DeliveryScheduler scheduler() {
        return new DeliveryScheduler(deliveryService, new BusinessMetrics(meterRegistry),
                new DeliveryProperties(Duration.ofMinutes(10), Duration.ofMinutes(30)));
    }
}
