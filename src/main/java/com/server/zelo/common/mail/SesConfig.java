package com.server.zelo.common.mail;

import io.awspring.cloud.autoconfigure.ses.SesClientCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
@ConditionalOnProperty(name = "zelo.mail.mode", havingValue = "real")
public class SesConfig {
    @Bean
    public SesClientCustomizer sesClientCustomizer() {
        return builder -> builder.overrideConfiguration(c -> c
                .apiCallAttemptTimeout(Duration.ofSeconds(2)) // 1회 시도 상한(넘으면 그 시도를 끊고 재시도)
                .apiCallTimeout(Duration.ofSeconds(5)) // 재시도를 포함한 전체 상한(넘으면 실패)
        );
    }
}
