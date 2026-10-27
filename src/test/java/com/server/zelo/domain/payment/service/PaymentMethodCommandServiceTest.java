package com.server.zelo.domain.payment.service;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.payment.entity.PaymentMethod;
import com.server.zelo.domain.payment.entity.PaymentMethodType;
import com.server.zelo.domain.portone.client.PortOneClient;
import com.server.zelo.domain.portone.dto.response.PortOneBillingKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
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
}