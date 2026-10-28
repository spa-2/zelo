package com.server.zelo.domain.portone.client.mock;

import com.server.zelo.domain.payment.entity.PaymentMethodType;
import com.server.zelo.domain.payment.repository.PaymentRepository;
import com.server.zelo.domain.portone.dto.response.PortOneBillingKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@ExtendWith(MockitoExtension.class)
class MockPortOneClientTest {

    @InjectMocks
    private MockPortOneClient mockPortOneClient;

    @Mock
    private PaymentRepository paymentRepository;

    @Test
    void 가짜_모드에서는_빌링키_끝의_사용자_ID를_발급_고객으로_보고_카드로_반환한다() {
        // when
        PortOneBillingKey billingKey = mockPortOneClient.getBillingKey("fake-billing-key-1");

        // then
        assertThat(billingKey.billingKey()).isEqualTo("fake-billing-key-1");
        assertThat(billingKey.type()).isEqualTo(PaymentMethodType.CARD);
        assertThat(billingKey.displayName()).isEqualTo("테스트카드 1234");
        assertThat(billingKey.isOwnedBy(1L)).isTrue();
        assertThat(billingKey.isOwnedBy(2L)).isFalse();
    }

    @Test
    void 가짜_모드에서_빌링키를_삭제하면_예외_없이_끝난다() {
        // when // then
        assertThatCode(() -> mockPortOneClient.deleteBillingKey("fake-billing-key-1"))
                .doesNotThrowAnyException();
    }
}