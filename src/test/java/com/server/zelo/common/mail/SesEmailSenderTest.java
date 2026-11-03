package com.server.zelo.common.mail;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.common.monitoring.BusinessMetric;
import com.server.zelo.common.monitoring.BusinessMetrics;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.SendEmailRequest;
import software.amazon.awssdk.services.ses.model.SesException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

@ExtendWith(MockitoExtension.class)
class SesEmailSenderTest {

    private static final MailSenderProperties PROPERTIES = new MailSenderProperties("no-reply@zelo.com");

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @Mock
    private SesClient sesClient;

    @Test
    void 발신자_수신자_제목_본문을_담아_발송한다() {
        // given
        SesEmailSender sesEmailSender = new SesEmailSender(sesClient, new BusinessMetrics(meterRegistry), PROPERTIES);

        // when
        sesEmailSender.send("jieun@example.com", "[zelo] 이메일 검증 요청", "검증 코드는 123456 입니다.");

        // then
        SendEmailRequest request = captureRequest();
        assertThat(request.source()).isEqualTo("no-reply@zelo.com");
        assertThat(request.destination().toAddresses()).containsExactly("jieun@example.com");
        assertThat(request.message().subject().data()).isEqualTo("[zelo] 이메일 검증 요청");
        assertThat(request.message().body().text().data()).isEqualTo("검증 코드는 123456 입니다.");
    }

    @Test
    void 제목과_본문의_문자_집합을_UTF_8로_지정한다() {
        // given
        SesEmailSender sesEmailSender = new SesEmailSender(sesClient, new BusinessMetrics(meterRegistry), PROPERTIES);

        // when
        sesEmailSender.send("jieun@example.com", "한글 제목", "한글 본문");

        // then 지정하지 않으면 SES가 7-bit ASCII로 처리해 한글이 깨진다
        SendEmailRequest request = captureRequest();
        assertThat(request.message().subject().charset()).isEqualTo("UTF-8");
        assertThat(request.message().body().text().charset()).isEqualTo("UTF-8");
    }

    @Test
    void SES가_거절하면_메일_발송_실패_예외로_바꾼다() {
        // given
        SesEmailSender sesEmailSender = new SesEmailSender(sesClient, new BusinessMetrics(meterRegistry), PROPERTIES);
        willThrow(SesException.builder().message("rejected").build()).given(sesClient).sendEmail(any(SendEmailRequest.class));

        // when & then
        assertThatThrownBy(() -> sesEmailSender.send("jieun@example.com", "제목", "본문"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.EMAIL_SEND_FAILED);
    }

    @Test
    void 네트워크_오류나_타임아웃도_메일_발송_실패_예외로_바꾼다() {
        // given
        SesEmailSender sesEmailSender = new SesEmailSender(sesClient, new BusinessMetrics(meterRegistry), PROPERTIES);
        willThrow(SdkClientException.create("timeout")).given(sesClient).sendEmail(any(SendEmailRequest.class));

        // when & then
        assertThatThrownBy(() -> sesEmailSender.send("jieun@example.com", "제목", "본문"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.EMAIL_SEND_FAILED);
    }

    @Test
    void 발송에_성공하면_성공_결과로_호출_시간을_기록한다() {
        // given
        SesEmailSender sesEmailSender = new SesEmailSender(sesClient, new BusinessMetrics(meterRegistry), PROPERTIES);

        // when
        sesEmailSender.send("jieun@example.com", "제목", "본문");

        // then
        assertThat(emailCallCount("SUCCESS")).isEqualTo(1);
        assertThat(emailCallCount("FAILURE")).isZero();
    }

    @Test
    void 발송에_실패하면_실패_결과로_호출_시간을_기록한다() {
        // given
        SesEmailSender sesEmailSender = new SesEmailSender(sesClient, new BusinessMetrics(meterRegistry), PROPERTIES);
        willThrow(SesException.builder().message("rejected").build()).given(sesClient).sendEmail(any(SendEmailRequest.class));

        // when
        assertThatThrownBy(() -> sesEmailSender.send("jieun@example.com", "제목", "본문"))
                .isInstanceOf(BusinessException.class);

        // then
        assertThat(emailCallCount("FAILURE")).isEqualTo(1);
        assertThat(emailCallCount("SUCCESS")).isZero();
    }

    private long emailCallCount(String outcome) {
        Timer timer = meterRegistry.find(BusinessMetric.EMAIL_API_CALL.getKey()).tag("outcome", outcome).timer();
        return timer == null ? 0 : timer.count();
    }

    private SendEmailRequest captureRequest() {
        ArgumentCaptor<SendEmailRequest> captor = ArgumentCaptor.forClass(SendEmailRequest.class);
        then(sesClient).should().sendEmail(captor.capture());
        return captor.getValue();
    }
}
