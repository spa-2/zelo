package com.server.zelo.domain.portone.client;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.payment.entity.Payment;
import com.server.zelo.domain.payment.entity.PaymentMethodType;
import com.server.zelo.domain.payment.repository.PaymentRepository;
import com.server.zelo.domain.portone.client.mock.MockPortOneClient;
import com.server.zelo.domain.portone.dto.response.PortOneBillingKey;
import com.server.zelo.domain.portone.dto.response.PortOnePayment;
import com.server.zelo.domain.portone.entity.PortOneCancellationStatus;
import com.server.zelo.domain.portone.entity.PortOnePaymentStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.given;

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

    @Test
    void 가짜_모드에서_빌링키_결제를_요청하면_예외_없이_끝난다() {
        // when // then
        assertThatCode(() -> mockPortOneClient.payWithBillingKey("payment-1", "fake-billing-key-1", "티셔츠 외 1건", 15000L))
                .doesNotThrowAnyException();
    }

    @Test
    void 가짜_모드에서_결제를_조회하면_DB_금액으로_결제_완료를_반환한다() {
        // given
        given(paymentRepository.findByPortoneId("payment-1"))
                .willReturn(Optional.of(Payment.of(1L, "payment-1", 15000L)));

        // when
        PortOnePayment payment = mockPortOneClient.getPayment("payment-1");

        // then
        assertThat(payment.portoneId()).isEqualTo("payment-1");
        assertThat(payment.status()).isEqualTo(PortOnePaymentStatus.PAID);
        assertThat(payment.amount()).isEqualTo(15000L);
    }

    @Test
    void 가짜_모드에서_없는_결제를_조회하면_예외가_발생한다() {
        // given
        given(paymentRepository.findByPortoneId("payment-1")).willReturn(Optional.empty());

        // when // then
        assertThatThrownBy(() -> mockPortOneClient.getPayment("payment-1"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PAYMENT_NOT_FOUND));
    }

    @Test
    void 가짜_모드에서_결제를_취소하면_예외_없이_끝난다() {
        // when // then
        assertThatCode(() -> mockPortOneClient.cancelPayment("payment-1", "테스트 취소"))
                .doesNotThrowAnyException();
    }

    @Test
    void 가짜_모드에서_금액을_지정해_취소하면_취소_성공을_반환한다() {
        // when
        PortOneCancellationStatus status = mockPortOneClient.cancelPaymentWithAmount(
                "payment-1", "refund-1", 5000L, 15000L, "부분 환불");

        // then
        assertThat(status).isEqualTo(PortOneCancellationStatus.SUCCEEDED);
    }

    @Test
    void 지연을_설정하면_그만큼_기다린_뒤_빌링키_결제를_끝낸다() {
        // given
        ReflectionTestUtils.setField(mockPortOneClient, "fakeBillingDelay", Duration.ofMillis(50));
        long startedAt = System.nanoTime();

        // when
        mockPortOneClient.payWithBillingKey("payment-1", "fake-billing-key-1", "티셔츠 외 1건", 15000L);

        // then
        assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isGreaterThanOrEqualTo(Duration.ofMillis(50));
    }

    @Test
    void 기다리는_중_인터럽트되면_예외_없이_끝나고_인터럽트_상태를_남긴다() {
        // given
        ReflectionTestUtils.setField(mockPortOneClient, "fakeDelay", Duration.ofSeconds(1));
        Thread.currentThread().interrupt();

        // when // then
        assertThatCode(() -> mockPortOneClient.deleteBillingKey("fake-billing-key-1"))
                .doesNotThrowAnyException();
        // interrupted()는 상태를 지워서 다음 테스트에 남지 않는다
        assertThat(Thread.interrupted()).isTrue();
    }
}