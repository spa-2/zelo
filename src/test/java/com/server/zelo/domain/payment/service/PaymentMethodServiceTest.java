package com.server.zelo.domain.payment.service;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.payment.entity.PaymentMethod;
import com.server.zelo.domain.payment.entity.PaymentMethodType;
import com.server.zelo.domain.payment.repository.PaymentMethodRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class PaymentMethodServiceTest {

    private static final Long USER_ID = 1L;
    private static final String BILLING_KEY = "billing-key-1";

    @InjectMocks
    private PaymentMethodService paymentMethodService;

    @Mock
    private PaymentMethodRepository paymentMethodRepository;

    @Test
    void 처음_등록하는_빌링키면_결제_수단을_저장한다() {
        // given
        given(paymentMethodRepository.existsByBillingKey(BILLING_KEY)).willReturn(false);
        given(paymentMethodRepository.save(any(PaymentMethod.class))).willAnswer(invocation -> invocation.getArgument(0));

        // when
        PaymentMethod saved = paymentMethodService.createPaymentMethod(
                USER_ID, PaymentMethodType.CARD, BILLING_KEY, "신한카드 1234");

        // then
        assertThat(saved.getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getBillingKey()).isEqualTo(BILLING_KEY);
    }

    @Test
    void 이미_등록된_빌링키면_예외가_발생하고_저장하지_않는다() {
        // given
        given(paymentMethodRepository.existsByBillingKey(BILLING_KEY)).willReturn(true);

        // when // then
        assertThatThrownBy(() -> paymentMethodService.createPaymentMethod(
                USER_ID, PaymentMethodType.CARD, BILLING_KEY, "신한카드 1234"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.PAYMENT_METHOD_ALREADY_REGISTERED);
        then(paymentMethodRepository).should(never()).save(any());
    }

    @Test
    void 본인의_결제_수단을_조회한다() {
        // given
        PaymentMethod paymentMethod = PaymentMethod.of(USER_ID, PaymentMethodType.CARD, BILLING_KEY, "신한카드 1234");
        given(paymentMethodRepository.findByIdAndUserId(3L, USER_ID)).willReturn(Optional.of(paymentMethod));

        // when // then
        assertThat(paymentMethodService.getOwnedMethod(3L, USER_ID)).isSameAs(paymentMethod);
    }

    @Test
    void 없거나_다른_사용자의_결제_수단이면_예외가_발생한다() {
        // given
        given(paymentMethodRepository.findByIdAndUserId(3L, USER_ID)).willReturn(Optional.empty());

        // when // then
        assertThatThrownBy(() -> paymentMethodService.getOwnedMethod(3L, USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.PAYMENT_METHOD_NOT_FOUND);
    }

    @Test
    void 동시에_같은_빌링키가_저장되어_유니크_제약에_걸리면_이미_등록된_결제_수단_예외로_바꾼다() {
        // given
        given(paymentMethodRepository.existsByBillingKey(BILLING_KEY)).willReturn(false);
        given(paymentMethodRepository.save(any(PaymentMethod.class))).willThrow(
                new DataIntegrityViolationException("duplicate", new SQLException("duplicate", "23505")));

        // when // then
        assertThatThrownBy(() -> paymentMethodService.createPaymentMethod(
                USER_ID, PaymentMethodType.CARD, BILLING_KEY, "신한카드 1234"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.PAYMENT_METHOD_ALREADY_REGISTERED);
    }

    @Test
    void 유니크_제약이_아닌_DB_오류는_그대로_던진다() {
        // given
        given(paymentMethodRepository.existsByBillingKey(BILLING_KEY)).willReturn(false);
        given(paymentMethodRepository.save(any(PaymentMethod.class))).willThrow(
                new DataIntegrityViolationException("not null", new SQLException("not null", "23502")));

        // when // then
        assertThatThrownBy(() -> paymentMethodService.createPaymentMethod(
                USER_ID, PaymentMethodType.CARD, BILLING_KEY, "신한카드 1234"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}