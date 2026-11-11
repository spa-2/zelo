package com.server.zelo.domain.user.social.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties("zelo.social.kakao")
public record KakaoOAuthProperties(
        @NotBlank
        String clientId,
        @NotBlank
        String clientSecret,
        @NotBlank
        String tokenUri,
        @NotBlank
        String userInfoUri,
        @NotNull
        Duration connectTimeout,
        @NotNull
        Duration readTimeout
) {

}
