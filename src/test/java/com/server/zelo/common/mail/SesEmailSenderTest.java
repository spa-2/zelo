package com.server.zelo.common.mail;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.MailSender;
import org.springframework.mail.SimpleMailMessage;
import software.amazon.awssdk.core.exception.SdkClientException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

@ExtendWith(MockitoExtension.class)
class SesEmailSenderTest {

    private static final MailSenderProperties PROPERTIES = new MailSenderProperties("no-reply@zelo.com");

    @Mock
    private MailSender mailSender;

    @Test
    void 발신자_수신자_제목_본문을_담아_발송한다() {
        // given
        SesEmailSender sesEmailSender = new SesEmailSender(mailSender, PROPERTIES);

        // when
        sesEmailSender.send("jieun@example.com", "제목", "코드는 123456");

        // then
        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        then(mailSender).should().send(captor.capture());
        SimpleMailMessage message = captor.getValue();
        assertThat(message.getFrom()).isEqualTo("no-reply@zelo.com");
        assertThat(message.getTo()).containsExactly("jieun@example.com");
        assertThat(message.getSubject()).isEqualTo("제목");
        assertThat(message.getText()).isEqualTo("코드는 123456");
    }

    @Test
    void SES가_거절하면_메일_발송_실패_예외로_바꾼다() {
        // given
        SesEmailSender sesEmailSender = new SesEmailSender(mailSender, PROPERTIES);
        willThrow(new MailSendException("rejected")).given(mailSender).send(org.mockito.ArgumentMatchers.any(SimpleMailMessage.class));

        // when & then
        assertThatThrownBy(() -> sesEmailSender.send("jieun@example.com", "제목", "본문"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.EMAIL_SEND_FAILED);
    }

    @Test
    void 네트워크_오류나_타임아웃도_메일_발송_실패_예외로_바꾼다() {
        // given
        SesEmailSender sesEmailSender = new SesEmailSender(mailSender, PROPERTIES);
        willThrow(SdkClientException.create("timeout")).given(mailSender).send(org.mockito.ArgumentMatchers.any(SimpleMailMessage.class));

        // when & then SES 거절과 달리 구현체가 감싸지 않고 그대로 던지는 예외
        assertThatThrownBy(() -> sesEmailSender.send("jieun@example.com", "제목", "본문"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.EMAIL_SEND_FAILED);
    }
}
