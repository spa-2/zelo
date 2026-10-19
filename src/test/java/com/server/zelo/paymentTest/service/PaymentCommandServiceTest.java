package com.server.zelo.paymentTest.service;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.order.mock.dto.MockOrder;
import com.server.zelo.domain.order.mock.service.MockOrderService;
import com.server.zelo.domain.payment.dto.response.CreatePaymentResponse;
import com.server.zelo.domain.payment.entity.Payment;
import com.server.zelo.domain.payment.entity.PaymentStatus;
import com.server.zelo.domain.payment.service.PaymentCommandService;
import com.server.zelo.domain.payment.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class PaymentCommandServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long ORDER_ID = 10L;

    @InjectMocks
    private PaymentCommandService paymentCommandService;

    @Mock
    private PaymentService paymentService;

    @Mock
    private MockOrderService mockOrderService;

    @Test
    void 결제_대기_주문이면_주문_금액으로_READY_결제를_생성한다() {
        // given
        MockOrder order = MockOrder.of(ORDER_ID, USER_ID, "김치볶음밥 재료", 32_000L, true);
        Payment payment = Payment.of(ORDER_ID, "payment-uuid-1", 32_000L);
        given(mockOrderService.getOrder(ORDER_ID)).willReturn(order);
        given(paymentService.findReadyPayment(ORDER_ID)).willReturn(Optional.empty());
        given(paymentService.createPayment(ORDER_ID, 32_000L)).willReturn(payment);

        // when
        CreatePaymentResponse response = paymentCommandService.createPayment(USER_ID, ORDER_ID);

        // then
        assertThat(response.orderId()).isEqualTo(ORDER_ID);
        assertThat(response.portoneId()).isEqualTo("payment-uuid-1");
        assertThat(response.orderName()).isEqualTo("김치볶음밥 재료");
        assertThat(response.amount()).isEqualTo(32_000L);
        assertThat(response.status()).isEqualTo(PaymentStatus.READY);
    }

    @Test
    void 같은_주문에_READY_결제가_있으면_새로_만들지_않고_기존_결제를_반환한다() {
        // given
        MockOrder order = MockOrder.of(ORDER_ID, USER_ID, "김치볶음밥 재료", 32_000L, true);
        Payment readyPayment = Payment.of(ORDER_ID, "payment-uuid-ready", 32_000L);
        given(mockOrderService.getOrder(ORDER_ID)).willReturn(order);
        given(paymentService.findReadyPayment(ORDER_ID)).willReturn(Optional.of(readyPayment));

        // when
        CreatePaymentResponse response = paymentCommandService.createPayment(USER_ID, ORDER_ID);

        // then
        assertThat(response.portoneId()).isEqualTo("payment-uuid-ready");
        then(paymentService).should(never()).createPayment(any(), any());
    }

    @Test
    void 결제_대기_상태가_아닌_주문이면_예외가_발생한다() {
        // given
        MockOrder order = MockOrder.of(ORDER_ID, USER_ID, "김치볶음밥 재료", 32_000L, false);
        given(mockOrderService.getOrder(ORDER_ID)).willReturn(order);

        // when // then
        assertThatThrownBy(() -> paymentCommandService.createPayment(USER_ID, ORDER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ORDER_NOT_PAYABLE);
        then(paymentService).shouldHaveNoInteractions();
    }

    @Test
    void 본인_주문이_아니면_예외가_발생한다() {
        // given
        MockOrder order = MockOrder.of(ORDER_ID, 999L, "김치볶음밥 재료", 32_000L, true);
        given(mockOrderService.getOrder(ORDER_ID)).willReturn(order);

        // when // then
        assertThatThrownBy(() -> paymentCommandService.createPayment(USER_ID, ORDER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ORDER_ACCESS_DENIED);
        then(paymentService).shouldHaveNoInteractions();
    }
}
