package com.server.zelo.domain.payment.service;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.payment.entity.Payment;
import com.server.zelo.domain.payment.entity.PaymentStatus;
import com.server.zelo.domain.payment.repository.PaymentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    private static final String PORTONE_ID = "payment-uuid-1";

    @InjectMocks
    private PaymentService paymentService;

    @Mock
    private PaymentRepository paymentRepository;

    @Test
    void 결제를_생성하면_포트원_결제_ID를_발급하고_READY로_저장한다() {
        // given
        given(paymentRepository.save(any(Payment.class))).willAnswer(invocation -> invocation.getArgument(0));

        // when
        Payment payment = paymentService.createPayment(10L, 32_000L);

        // then
        assertThat(payment.getOrderId()).isEqualTo(10L);
        assertThat(payment.getAmount()).isEqualTo(32_000L);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.READY);
        assertThat(payment.getPortoneId()).startsWith("payment-").hasSize(40);
    }

    @Test
    void 포트원_결제_ID로_결제가_없으면_예외가_발생한다() {
        // given
        given(paymentRepository.findByPortoneId(PORTONE_ID)).willReturn(Optional.empty());

        // when // then
        assertThatThrownBy(() -> paymentService.getPayment(PORTONE_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.PAYMENT_NOT_FOUND);
    }

    @Test
    void READY_결제의_확인을_시작하면_확인_대기가_된다() {
        // given
        Payment payment = Payment.of(10L, PORTONE_ID, 32_000L);
        given(paymentRepository.findByPortoneId(PORTONE_ID)).willReturn(Optional.of(payment));

        // when
        Payment result = paymentService.startConfirm(PORTONE_ID);

        // then
        assertThat(result.getStatus()).isEqualTo(PaymentStatus.CONFIRM_PENDING);
    }

    @Test
    void 이미_확인_대기인_결제는_상태를_바꾸지_않고_반환한다() {
        // given
        Payment payment = Payment.of(10L, PORTONE_ID, 32_000L);
        payment.startConfirm();
        given(paymentRepository.findByPortoneId(PORTONE_ID)).willReturn(Optional.of(payment));

        // when
        Payment result = paymentService.startConfirm(PORTONE_ID);

        // then
        assertThat(result.getStatus()).isEqualTo(PaymentStatus.CONFIRM_PENDING);
    }

    @Test
    void 이미_완료된_결제는_상태를_바꾸지_않고_반환한다() {
        // given
        Payment payment = Payment.of(10L, PORTONE_ID, 32_000L);
        payment.startConfirm();
        payment.complete(32_000L, LocalDateTime.now());
        given(paymentRepository.findByPortoneId(PORTONE_ID)).willReturn(Optional.of(payment));

        // when
        Payment result = paymentService.startConfirm(PORTONE_ID);

        // then
        assertThat(result.getStatus()).isEqualTo(PaymentStatus.PAID);
    }

    @Test
    void 주문의_가장_최근_결제를_돌려준다() {
        // given
        Payment payment = Payment.of(10L, "payment-uuid-1", 32_000L);
        given(paymentRepository.findTopByOrderIdOrderByIdDesc(10L)).willReturn(Optional.of(payment));

        // when
        Optional<Payment> result = paymentService.findLatestPayment(10L);

        // then
        assertThat(result).containsSame(payment);
    }

    @Test
    void 주문에_결제가_없으면_빈_Optional을_돌려준다() {
        // given
        given(paymentRepository.findTopByOrderIdOrderByIdDesc(10L)).willReturn(Optional.empty());

        // when
        Optional<Payment> result = paymentService.findLatestPayment(10L);

        // then
        assertThat(result).isEmpty();
    }


}
