package com.server.zelo.domain.order.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 구매 확인 정보는 주문이 생기기 전에 잠깐 쓰고 버리는 1회용 데이터라,
 * orders 테이블(JPA)을 다루는 OrderRepository가 아니라
 * 만료 시각과 함께 Redis에 저장하는 별도 저장소로 분리함
 */
@Repository
@RequiredArgsConstructor
public class OrderConfirmationRepository {

    private static final String KEY_PREFIX = "order:confirmation:";

    private final JsonMapper jsonMapper;
    private final StringRedisTemplate stringRedisTemplate;

    // Redis에 사용자 ID·roomId·quoteId 저장
    // 근거: 주문 생성 때 구매 확인을 거친 요청인지 검증할 수 있도록, 확인 번호별로 요청한 사용자·채팅방·견적을 남김
    public void save(
            String confirmationId,
            Long userId,
            Long roomId,
            String quoteId,
            LocalDateTime expiresAt
    ) {
        String key = KEY_PREFIX + confirmationId;

        String json = jsonMapper.writeValueAsString(Map.of(
                "userId", userId,
                "roomId", roomId,
                "quoteId", quoteId
        ));

        // 현재 견적 생성과 같은 서버 시간대를 적용
        // 근거: 견적의 만료 시각은 서버 기본 시간대 기준의 LocalDateTime이라, 같은 시간대로 바꿔야 만료 시각이 어긋나지 않음.
        long expiresAtMillis = expiresAt
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli();

        // 발급 시점부터 유효시간을 다시 늘리지 않도록 견적의 만료 시각을 그대로 사용
        /* 근거: 남은 시간(TTL)이 아니라 견적의 만료 시각으로 만료시켜, 확인 번호가 견적보다 오래 남지 않게 함.
         * 이미 지난 시각이면 Redis가 바로 만료시킴
         */
        stringRedisTemplate.opsForValue()
                .set(key, json, Expiration.unixTimestamp(expiresAtMillis, TimeUnit.MILLISECONDS)
        );
    }
}