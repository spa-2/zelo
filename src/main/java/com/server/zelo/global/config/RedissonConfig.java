package com.server.zelo.global.config;

import org.redisson.spring.starter.RedissonAutoConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// 응답 대기 시간은 application.yaml의 spring.data.redis.timeout으로 정하고, 재시도 횟수는 yaml로 못 바꿔서 여기서 정한다
// 기본 4번이면 Redis가 멈췄을 때 명령 하나가 재시도마다 기다려서 몇 초씩 걸리므로 1번만 다시 보낸다
@Configuration
public class RedissonConfig {

    private static final int RETRY_ATTEMPTS = 1;

    @Bean
    public RedissonAutoConfigurationCustomizer redissonRetryCustomizer() {
        return config -> config.useSingleServer().setRetryAttempts(RETRY_ATTEMPTS);
    }
}
