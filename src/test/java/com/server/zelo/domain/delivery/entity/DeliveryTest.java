package com.server.zelo.domain.delivery.entity;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeliveryTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 21, 12, 0);

    @Test
    void 배송을_생성하면_READY_상태이고_상태별_시각은_비어_있다() {
        // when
        Delivery delivery = createDelivery();

        // then
        assertThat(delivery.getOrderId()).isEqualTo(1L);
        assertThat(delivery.getRecipientName()).isEqualTo("홍길동");
        assertThat(delivery.getAddressDetail()).isEqualTo("101동 101호");
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.READY);
        assertThat(delivery.getShippedAt()).isNull();
        assertThat(delivery.getDeliveredAt()).isNull();
        assertThat(delivery.getCanceledAt()).isNull();
    }

    @Test
    void 상세_주소_없이도_배송을_생성할_수_있다() {
        // when
        Delivery delivery = Delivery.of(1L, "홍길동", "010-1234-5678", "06234", "서울시 강남구 테헤란로 1", null);

        // then
        assertThat(delivery.getAddressDetail()).isNull();
    }

    @Test
    void 주문_ID가_없으면_예외가_발생한다() {
        // when
        // then
        assertThatThrownBy(() -> Delivery.of(null, "홍길동", "010-1234-5678", "06234", "서울시 강남구 테헤란로 1", null))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void 받는_사람이_비어_있으면_예외가_발생한다(String recipientName) {
        // when
        // then
        assertThatThrownBy(() -> Delivery.of(1L, recipientName, "010-1234-5678", "06234", "서울시 강남구 테헤란로 1", null))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void 주소가_비어_있으면_예외가_발생한다(String address) {
        // when
        // then
        assertThatThrownBy(() -> Delivery.of(1L, "홍길동", "010-1234-5678", "06234", address, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
    }

    @Test
    void 출고하면_배송_중_상태가_되고_출고_시각을_저장한다() {
        // given
        Delivery delivery = createDelivery();

        // when
        delivery.ship(NOW);

        // then
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.SHIPPING);
        assertThat(delivery.getShippedAt()).isEqualTo(NOW);
    }

    @Test
    void 배송을_완료하면_완료_시각을_저장한다() {
        // given
        Delivery delivery = createDelivery();
        delivery.ship(NOW);

        // when
        delivery.complete(NOW.plusDays(1));

        // then
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.DELIVERED);
        assertThat(delivery.getDeliveredAt()).isEqualTo(NOW.plusDays(1));
    }

    @Test
    void 배송_준비_중이면_취소하고_취소_시각을_저장한다() {
        // given
        Delivery delivery = createDelivery();

        // when
        delivery.cancel(NOW);

        // then
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.CANCELED);
        assertThat(delivery.getCanceledAt()).isEqualTo(NOW);
    }

    @Test
    void 출고된_배송은_취소할_수_없다() {
        // given
        Delivery delivery = createDelivery();
        delivery.ship(NOW);

        // when
        // then
        assertThatThrownBy(() -> delivery.cancel(NOW))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_DELIVERY_STATUS));
        assertThat(delivery.getCanceledAt()).isNull();
    }

    @Test
    void 배송_준비_중일_때만_취소_가능하다() {
        // given
        Delivery ready = createDelivery();
        Delivery shipping = createDelivery();
        shipping.ship(NOW);
        Delivery canceled = createDelivery();
        canceled.cancel(NOW);

        // when
        // then
        assertThat(ready.isCancelable()).isTrue();
        assertThat(shipping.isCancelable()).isFalse();
        assertThat(canceled.isCancelable()).isFalse();
    }

    private Delivery createDelivery() {
        return Delivery.of(1L, "홍길동", "010-1234-5678", "06234", "서울시 강남구 테헤란로 1", "101동 101호");
    }
}