package com.server.zelo.domain.order.service;

import com.server.zelo.domain.order.entity.OrderStatus;
import com.server.zelo.domain.order.repository.OrderConfirmationRepository;
import com.server.zelo.domain.order.repository.OrderRepository;
import com.server.zelo.domain.payment.entity.PaymentStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class OrderExpirationServiceTest {

    private static final LocalDateTime CUTOFF = LocalDateTime.of(2030, 1, 2, 12, 0);
    private static final List<PaymentStatus> BLOCKED =
            List.of(PaymentStatus.CONFIRM_PENDING, PaymentStatus.PAID);

    @Mock private OrderRepository orderRepository;
    @Mock private OrderConfirmationRepository confirmationRepository;
    @InjectMocks private OrderService service;

    @Test
    void 결제_확인중과_완료를_제외하고_첫_100건의_만료_주문_ID를_조회한다() {
        // given
        given(orderRepository.findExpiredOrderIds(eq(OrderStatus.PAYMENT_PENDING), eq(CUTOFF),
                eq(BLOCKED), any(Pageable.class))).willReturn(List.of(101L, 102L));

        // when
        List<Long> result = service.findExpiredOrderIds(CUTOFF);

        // then
        assertThat(result).containsExactly(101L, 102L);
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        then(orderRepository).should().findExpiredOrderIds(eq(OrderStatus.PAYMENT_PENDING),
                eq(CUTOFF), eq(BLOCKED), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isZero();
        assertThat(pageable.getValue().getPageSize()).isEqualTo(100);
        verifyNoInteractions(confirmationRepository);
    }

    @Test
    void 만료_후보가_없으면_빈_목록을_반환한다() {
        // given
        given(orderRepository.findExpiredOrderIds(eq(OrderStatus.PAYMENT_PENDING), eq(CUTOFF),
                eq(BLOCKED), any(Pageable.class))).willReturn(List.of());

        // when
        List<Long> result = service.findExpiredOrderIds(CUTOFF);

        // then
        assertThat(result).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void 취소_직전에도_후보_조회와_동일한_상태와_시각_기준으로_검증한다(boolean eligible) {
        // given
        given(orderRepository.existsExpiredOrder(101L, OrderStatus.PAYMENT_PENDING, CUTOFF, BLOCKED))
                .willReturn(eligible);

        // when
        boolean result = service.isExpiredOrder(101L, CUTOFF);

        // then
        assertThat(result).isEqualTo(eligible);
        then(orderRepository).should().existsExpiredOrder(
                101L, OrderStatus.PAYMENT_PENDING, CUTOFF, BLOCKED);
    }

    @Test
    void 후보_조회_실패를_빈_목록으로_숨기지_않는다() {
        // given
        IllegalStateException failure = new IllegalStateException("주문 조회 실패");
        given(orderRepository.findExpiredOrderIds(eq(OrderStatus.PAYMENT_PENDING), eq(CUTOFF),
                eq(BLOCKED), any(Pageable.class))).willThrow(failure);

        // when
        // then
        assertThatThrownBy(() -> service.findExpiredOrderIds(CUTOFF)).isSameAs(failure);
    }
}
