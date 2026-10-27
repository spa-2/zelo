package com.server.zelo.domain.payment.service;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.payment.entity.PaymentMethod;
import com.server.zelo.domain.payment.entity.PaymentMethodType;
import com.server.zelo.domain.portone.client.PortOneClient;
import com.server.zelo.domain.portone.dto.response.PortOneBillingKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.*;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class PaymentMethodCommandServiceTest {

    private static final Long USER_ID = 1L;
    private static final String BILLING_KEY = "billing-key-1";

    @InjectMocks
    private PaymentMethodCommandService paymentMethodCommandService;

    @Mock
    private PortOneClient portOneClient;

    @Mock
    private PaymentMethodService paymentMethodService;

    @Test
    void 포트원에서_확인한_종류와_표시_이름으로_결제_수단을_등록한다() {
        // given
        PaymentMethod paymentMethod = PaymentMethod.of(USER_ID, PaymentMethodType.CARD, BILLING_KEY, "신한카드 1234");
        given(portOneClient.getBillingKey(BILLING_KEY))
                .willReturn(PortOneBillingKey.of(BILLING_KEY, PaymentMethodType.CARD, "신한카드 1234", "1"));
        given(paymentMethodService.createPaymentMethod(USER_ID, PaymentMethodType.CARD, BILLING_KEY, "신한카드 1234"))
                .willReturn(paymentMethod);

        // when
        PaymentMethod created = paymentMethodCommandService.createPaymentMethod(USER_ID, BILLING_KEY);

        // then
        assertThat(created).isSameAs(paymentMethod);
    }

    @Test
    void 다른_사용자가_발급한_빌링키면_예외가_발생하고_저장하지_않는다() {
        // given
        given(portOneClient.getBillingKey(BILLING_KEY))
                .willReturn(PortOneBillingKey.of(BILLING_KEY, PaymentMethodType.CARD, "신한카드 1234", "2"));

        // when // then
        assertThatThrownBy(() -> paymentMethodCommandService.createPaymentMethod(USER_ID, BILLING_KEY))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_BILLING_KEY);
        then(paymentMethodService).should(never()).createPaymentMethod(any(), any(), any(), any());
    }

    @Test
    void 결제_수단을_삭제하면_포트원_빌링키를_먼저_지우고_DB에서_삭제한다() {
        // given
        PaymentMethod paymentMethod = PaymentMethod.of(USER_ID, PaymentMethodType.CARD, BILLING_KEY, "신한카드 1234");
        ReflectionTestUtils.setField(paymentMethod, "id", 3L);
        given(paymentMethodService.getOwnedMethod(3L, USER_ID)).willReturn(paymentMethod);

        // when
        paymentMethodCommandService.deletePaymentMethod(USER_ID, 3L);

        // then
        InOrder inOrder = inOrder(portOneClient, paymentMethodService);
        inOrder.verify(portOneClient).deleteBillingKey(BILLING_KEY);
        inOrder.verify(paymentMethodService).deletePaymentMethod(3L);
    }

    @Test
    void 본인의_결제_수단이_아니면_예외가_발생하고_포트원_빌링키를_지우지_않는다() {
        // given
        given(paymentMethodService.getOwnedMethod(3L, USER_ID))
                .willThrow(new BusinessException(ErrorCode.PAYMENT_METHOD_NOT_FOUND));

        // when // then
        assertThatThrownBy(() -> paymentMethodCommandService.deletePaymentMethod(USER_ID, 3L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.PAYMENT_METHOD_NOT_FOUND);
        then(portOneClient).should(never()).deleteBillingKey(any());
    }

    @Test
    void 포트원_빌링키_삭제가_실패하면_DB에서_삭제하지_않는다() {
        // given
        PaymentMethod paymentMethod = PaymentMethod.of(USER_ID, PaymentMethodType.CARD, BILLING_KEY, "신한카드 1234");
        ReflectionTestUtils.setField(paymentMethod, "id", 3L);
        given(paymentMethodService.getOwnedMethod(3L, USER_ID)).willReturn(paymentMethod);
        willThrow(new BusinessException(ErrorCode.PG_REQUEST_FAILED)).given(portOneClient).deleteBillingKey(BILLING_KEY);

        // when // then
        assertThatThrownBy(() -> paymentMethodCommandService.deletePaymentMethod(USER_ID, 3L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.PG_REQUEST_FAILED);
        then(paymentMethodService).should(never()).deletePaymentMethod(any());
    }
}