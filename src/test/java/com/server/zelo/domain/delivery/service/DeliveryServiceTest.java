package com.server.zelo.domain.delivery.service;

import com.server.zelo.domain.delivery.entity.Delivery;
import com.server.zelo.domain.delivery.entity.DeliveryStatus;
import com.server.zelo.domain.delivery.repository.DeliveryRepository;
import com.server.zelo.domain.user.entity.User;
import com.server.zelo.domain.user.entity.UserAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class DeliveryServiceTest {

    @Mock
    private DeliveryRepository deliveryRepository;

    @InjectMocks
    private DeliveryService deliveryService;

    @Test
    void 기본_배송지_정보로_배송_준비_상태의_배송을_만든다() {
        // given
        UserAddress address = UserAddress.of(mock(User.class), "집", "홍길동", "010-1234-5678", "12345", "서울시 강남구", true);
        given(deliveryRepository.save(any(Delivery.class))).willAnswer(invocation -> invocation.getArgument(0));

        // when
        Delivery delivery = deliveryService.createDelivery(10L, address);

        // then
        assertThat(delivery.getOrderId()).isEqualTo(10L);
        assertThat(delivery.getRecipientName()).isEqualTo("홍길동");
        assertThat(delivery.getRecipientPhone()).isEqualTo("010-1234-5678");
        assertThat(delivery.getZipCode()).isEqualTo("12345");
        assertThat(delivery.getAddress()).isEqualTo("서울시 강남구");
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.READY);
    }
}