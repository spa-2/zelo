package com.server.zelo.domain.delivery.scheduler;

import com.server.zelo.domain.delivery.config.DeliveryProperties;
import com.server.zelo.domain.delivery.service.DeliveryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class DeliverySchedulerTest {

    @Mock
    private DeliveryService deliveryService;

    @Test
    void 실행하면_배송_완료와_출고를_모두_처리한다() {
        // given
        DeliveryScheduler scheduler = new DeliveryScheduler(deliveryService,
                new DeliveryProperties(Duration.ofMinutes(10), Duration.ofMinutes(30)));

        // when
        scheduler.progressDeliveries();

        // then
        then(deliveryService).should().completeShippingDeliveries(any(), any());
        then(deliveryService).should().shipReadyDeliveries(any(), any());
    }
}
