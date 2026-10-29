package com.server.zelo.common.mail;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSender;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;

@Slf4j
@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(MailSenderProperties.class)
@ConditionalOnProperty(name = "zelo.mail.mode", havingValue = "real")
public class SesEmailSender implements EmailSender {

    private final MailSender mailSender;
    private final MailSenderProperties mailSenderProperties;

    @Override
    public void send(String to, String title, String body) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(mailSenderProperties.from());// 발신자
        message.setTo(to); // 수신자
        message.setSubject(title); // 제목
        message.setText(body); // 내용

        try {
            mailSender.send(message);
        } catch (MailException | SdkException e) {
            // 원인(타임아웃, SES 거절, 자격증명 등)은 여기서만 알 수 있어서 변환하기 전에 남긴다. 수신자는 개인정보라 남기지 않는다
            log.error("SES 메일 발송 실패", e);
            throw new BusinessException(ErrorCode.EMAIL_SEND_FAILED);
        }
    }
}
