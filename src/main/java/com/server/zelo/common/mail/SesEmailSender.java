package com.server.zelo.common.mail;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.Body;
import software.amazon.awssdk.services.ses.model.Content;
import software.amazon.awssdk.services.ses.model.Destination;
import software.amazon.awssdk.services.ses.model.Message;
import software.amazon.awssdk.services.ses.model.SendEmailRequest;

@Slf4j
@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(MailSenderProperties.class)
@ConditionalOnProperty(name = "zelo.mail.mode", havingValue = "real")
public class SesEmailSender implements EmailSender {

    // SES는 문자 집합이 없는 내용을 7-bit ASCII로 처리해서 한글이 깨지거나 거절된다
    // Spring Cloud AWS의 MailSender는 이를 지정할 수 없어서 SesClient로 직접 요청을 만든다
    private static final String CHARSET = "UTF-8";

    private final SesClient sesClient;
    private final MailSenderProperties mailSenderProperties;

    @Override
    public void send(String to, String title, String body) {
        SendEmailRequest request = SendEmailRequest.builder()
                .source(mailSenderProperties.from()) // 발신자
                .destination(Destination.builder().toAddresses(to).build()) // 수신자
                .message(Message.builder()
                        .subject(content(title)) // 제목
                        .body(Body.builder().text(content(body)).build()) // 내용
                        .build())
                .build();

        try {
            sesClient.sendEmail(request);
        } catch (SdkException e) {
            // 원인(타임아웃, SES 거절, 자격증명 등)은 여기서만 알 수 있어서 변환하기 전에 남긴다. 수신자는 개인정보라 남기지 않는다
            log.error("SES 메일 발송 실패", e);
            throw new BusinessException(ErrorCode.EMAIL_SEND_FAILED);
        }
    }

    private Content content(String data) {
        return Content.builder() // 보낼 제목, 데이터
                .data(data)  // 문자열
                .charset(CHARSET) // 문자집합 설정
                .build();
    }
}
