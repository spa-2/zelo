package com.server.zelo.common.mail;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(name = "zelo.mail.mode", havingValue = "fake")
public class FakeEmailSender implements EmailSender {

    @PostConstruct
    void warn() {
        log.warn("FakeEmailSender 활성화, 운영 환경이라면 절대 사용 금지");
    }

    @Override
    public void send(String to, String title, String body) {
        log.info(
                "[FAKE EMAIL] to={}, title={}, body={}",
                to,
                title,
                body
        );
    }
}
