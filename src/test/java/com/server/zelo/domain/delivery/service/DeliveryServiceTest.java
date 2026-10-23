package com.server.zelo.domain.delivery.service;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
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
import org.springframework.data.domain.Limit;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class DeliveryServiceTest {

    @Mock
    private DeliveryRepository deliveryRepository;

    @InjectMocks
    private DeliveryService deliveryService;
    private static final Limit BATCH = Limit.of(DeliveryService.BATCH_SIZE);

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

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 22, 12, 0);

    @Test
    void 배송_준비가_오래된_배송을_출고_처리한다() {
        // given
        Delivery delivery = readyDelivery();
        LocalDateTime readyBefore = NOW.minusMinutes(10);
        given(deliveryRepository.findByStatusAndOnHoldFalseAndUpdatedAtBeforeOrderByIdAsc(DeliveryStatus.READY, readyBefore, BATCH))
                .willReturn(List.of(delivery));

        // when
        int count = deliveryService.shipReadyDeliveries(readyBefore, NOW);

        // then
        assertThat(count).isEqualTo(1);
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.SHIPPING);
        assertThat(delivery.getShippedAt()).isEqualTo(NOW);
    }

    @Test
    void 출고_후_오래된_배송을_배송_완료_처리한다() {
        // given
        Delivery delivery = readyDelivery();
        delivery.ship(NOW.minusHours(1));
        LocalDateTime shippingBefore = NOW.minusMinutes(30);
        given(deliveryRepository.findByStatusAndOnHoldFalseAndUpdatedAtBeforeOrderByIdAsc(DeliveryStatus.SHIPPING, shippingBefore, BATCH))
                .willReturn(List.of(delivery));

        // when
        int count = deliveryService.completeShippingDeliveries(shippingBefore, NOW);

        // then
        assertThat(count).isEqualTo(1);
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.DELIVERED);
        assertThat(delivery.getDeliveredAt()).isEqualTo(NOW);
    }

    @Test
    void 진행할_배송이_없으면_아무것도_바꾸지_않는다() {
        // given
        LocalDateTime readyBefore = NOW.minusMinutes(10);
        given(deliveryRepository.findByStatusAndOnHoldFalseAndUpdatedAtBeforeOrderByIdAsc(DeliveryStatus.READY, readyBefore, BATCH))
                .willReturn(List.of());

        // when
        int count = deliveryService.shipReadyDeliveries(readyBefore, NOW);

        // then
        assertThat(count).isZero();
    }

    @Test
    void 배송_준비_중이면_환불을_위해_보류한다() {
        // given
        Delivery delivery = readyDelivery();
        given(deliveryRepository.findByOrderId(10L)).willReturn(Optional.of(delivery));

        // when
        deliveryService.holdForRefund(10L);

        // then
        assertThat(delivery.isOnHold()).isTrue();
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.READY);
    }

    @Test
    void 이미_출고된_배송은_환불할_수_없다() {
        // given
        Delivery delivery = readyDelivery();
        delivery.ship(NOW);
        given(deliveryRepository.findByOrderId(10L)).willReturn(Optional.of(delivery));

        // when
        // then
        assertThatThrownBy(() -> deliveryService.holdForRefund(10L))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.DELIVERY_NOT_REFUNDABLE));
        assertThat(delivery.isOnHold()).isFalse();
    }

    @Test
    void 부분_환불이나_실패면_보류만_풀고_배송_준비를_유지한다() {
        // given
        Delivery delivery = readyDelivery();
        delivery.hold();
        given(deliveryRepository.findByOrderId(10L)).willReturn(Optional.of(delivery));

        // when
        deliveryService.releaseHold(10L);

        // then
        assertThat(delivery.isOnHold()).isFalse();
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.READY);
    }

    @Test
    void 전액_환불이면_배송을_취소한다() {
        // given
        Delivery delivery = readyDelivery();
        delivery.hold();
        given(deliveryRepository.findByOrderId(10L)).willReturn(Optional.of(delivery));

        // when
        deliveryService.cancelDelivery(10L);

        // then
        assertThat(delivery.isOnHold()).isFalse();
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.CANCELED);
        assertThat(delivery.getCanceledAt()).isNotNull();
    }

    @Test
    void 배송이_없으면_DELIVERY_NOT_FOUND가_발생한다() {
        // given
        given(deliveryRepository.findByOrderId(10L)).willReturn(Optional.empty());

        // when
        // then
        assertThatThrownBy(() -> deliveryService.holdForRefund(10L))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.DELIVERY_NOT_FOUND));
    }

    private Delivery readyDelivery() {
        return Delivery.of(10L, "홍길동", "010-1234-5678", "12345", "서울시 강남구", null);
    }
}