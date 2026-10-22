package com.server.zelo.domain.webhook.service;
import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.webhook.dto.request.WebhookRequest;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PortOneWebhookVerifierTest {

    private static final byte[] SECRET_KEY = "zelo-test-webhook-secret-key-32b".getBytes(StandardCharsets.UTF_8);
    private static final String SECRET = "whsec_" + Base64.getEncoder().encodeToString(SECRET_KEY);
    private static final String WEBHOOK_ID = "wh-1";
    private static final String BODY = """
            {"type":"Transaction.Paid","timestamp":"2026-10-21T12:00:00Z","data":{"paymentId":"payment-uuid-1","storeId":"store-id","transactionId":"tx-1"}}""";

    private final PortOneWebhookVerifier verifier = new PortOneWebhookVerifier(
            SECRET, JsonMapper.builder().build(), Validation.buildDefaultValidatorFactory().getValidator());

    @Test
    void 서명이_맞으면_웹훅_본문을_객체로_돌려준다() {
        // given
        String timestamp = now();

        // when
      WebhookRequest request = verifier.verify(WEBHOOK_ID, timestamp, sign(timestamp, BODY), BODY);

        // then
        assertThat(request.type()).isEqualTo("Transaction.Paid");
        assertThat(request.data().paymentId()).isEqualTo("payment-uuid-1");
    }

    @Test
    void 서명이_다르면_INVALID_WEBHOOK_SIGNATURE가_발생한다() {
        // when
        // then
        assertThatThrownBy(() -> verifier.verify(WEBHOOK_ID, now(), "v1,wrong-signature", BODY))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_WEBHOOK_SIGNATURE));
    }

    @Test
    void 본문이_바뀌면_INVALID_WEBHOOK_SIGNATURE가_발생한다() {
        // given
        String timestamp = now();
        String signature = sign(timestamp, BODY);
        String tamperedBody = BODY.replace("payment-uuid-1", "payment-uuid-2");

        // when
        // then
        assertThatThrownBy(() -> verifier.verify(WEBHOOK_ID, timestamp, signature, tamperedBody))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_WEBHOOK_SIGNATURE));
    }

    @Test
    void 서명은_맞지만_결제_ID가_없으면_INVALID_INPUT이_발생한다() {
        // given
        String timestamp = now();
        String body = """
                {"type":"Transaction.Paid","data":{}}""";

        // when
        // then
        assertThatThrownBy(() -> verifier.verify(WEBHOOK_ID, timestamp, sign(timestamp, body), body))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
    }

    @ParameterizedTest
    @NullAndEmptySource
    void 웹훅_시크릿이_없으면_웹훅을_거절한다(String secret) {
        // given
        PortOneWebhookVerifier noSecretVerifier = new PortOneWebhookVerifier(
                secret, JsonMapper.builder().build(), Validation.buildDefaultValidatorFactory().getValidator());
        String timestamp = now();

        // when
        // then
        assertThatThrownBy(() -> noSecretVerifier.verify(WEBHOOK_ID, timestamp, sign(timestamp, BODY), BODY))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_WEBHOOK_SIGNATURE));
    }

    @ParameterizedTest
    @NullAndEmptySource
    void 서명_헤더가_없으면_INVALID_WEBHOOK_SIGNATURE가_발생한다(String signature) {
                assertThatThrownBy(() -> verifier.verify(WEBHOOK_ID, now(), signature, BODY))
                                .isInstanceOfSatisfying(BusinessException.class,
                                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_WEBHOOK_SIGNATURE));
            }

            @Test
                void timestamp가_오래되면_INVALID_WEBHOOK_SIGNATURE가_발생한다() {
                String timestamp = "0";

                        assertThatThrownBy(() -> verifier.verify(WEBHOOK_ID, timestamp, sign(timestamp, BODY), BODY))
                               .isInstanceOfSatisfying(BusinessException.class,
                                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_WEBHOOK_SIGNATURE));
            }

    private String now() {
        return String.valueOf(Instant.now().getEpochSecond());
    }

    // 포트원과 같은 Standard Webhooks 방식으로 서명을 만든다
    private String sign(String timestamp, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET_KEY, "HmacSHA256"));
            byte[] raw = mac.doFinal((WEBHOOK_ID + "." + timestamp + "." + body).getBytes(StandardCharsets.UTF_8));
            return "v1," + Base64.getEncoder().encodeToString(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}