package com.server.zelo.domain.user.social.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(KakaoOAuthProperties.class)
@ConditionalOnProperty(name = "zelo.social.mode", havingValue = "real")
public class KakaoSocialConfig {

    @Bean
    public RestClient kakaoSocialRestClient(KakaoOAuthProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());

        return RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }
}
