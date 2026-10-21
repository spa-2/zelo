package com.server.zelo.domain.quote.repository;

import com.server.zelo.domain.quote.model.Quote;
import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
@RequiredArgsConstructor
public class QuoteRepository {

    public static final Duration QUOTE_TTL = Duration.ofMinutes(10);

    private static final String KEY_PREFIX = "quote:";

    private final JsonMapper jsonMapper;
    private final StringRedisTemplate stringRedisTemplate;

    // 채팅방마다 견적은 하나라 발급·수정 때마다 덮어쓰고 TTL도 다시 10분
    public void save(Quote quote) {
        stringRedisTemplate.opsForValue().set(key(quote.roomId()), jsonMapper.writeValueAsString(quote), QUOTE_TTL);
    }

    // 없으면 만료됐거나 발급 전 (둘은 구분 안 됨)
    public Optional<Quote> findByRoomId(Long roomId) {
        String json = stringRedisTemplate.opsForValue().get(key(roomId));
        if (json == null) {
            return Optional.empty();
        }
        return Optional.of(jsonMapper.readValue(json, Quote.class));
    }

    private String key(Long roomId) {
        return KEY_PREFIX + roomId;
    }
}