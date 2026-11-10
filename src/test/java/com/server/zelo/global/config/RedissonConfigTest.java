package com.server.zelo.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.redisson.config.SingleServerConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

// Redis가 멈췄을 때 빨리 실패하도록 줄인 응답 대기·재시도가 실제 Redisson 설정에 들어갔는지 확인한다
@SpringBootTest
@ActiveProfiles("test")
class RedissonConfigTest {

    @Autowired
    private RedissonClient redissonClient;

    @Test
    void Redis_응답_대기는_1초_재시도는_1번이다() {
        SingleServerConfig config = redissonClient.getConfig().useSingleServer();

        assertThat(config.getTimeout()).isEqualTo(1_000);
        assertThat(config.getRetryAttempts()).isEqualTo(1);
    }
}
