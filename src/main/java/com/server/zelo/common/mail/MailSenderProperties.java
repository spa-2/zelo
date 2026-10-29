package com.server.zelo.common.mail;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("zelo.mail")
// 중립적인 이름을 사용하여 다른 메일 제공자를 사용하더라도 동일하게 사용
public record MailSenderProperties(@NotBlank String from) {
}
