package com.server.zelo.domain.product.repository;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.connection.zset.Aggregate;
import org.springframework.data.redis.connection.zset.Weights;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.stereotype.Repository;

// 검색어를 한 시간 단위 ZSET(search:keyword:2026102814)에 세고, 최근 24시간치를 합쳐서 순위를 냄
// 시간대별로 키를 나눠서 TTL로 오래된 검색어가 저절로 빠지게 함
@Repository
@RequiredArgsConstructor
public class PopularKeywordRepository {

    private static final String KEY_PREFIX = "search:keyword:";
    private static final String TOP_KEY = "search:keyword:top";
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("yyyyMMddHH");
    private static final int WINDOW_HOURS = 24;
    // 합칠 때 24시간 전 키까지 남아 있게 한 시간 여유를 둠
    private static final Duration TTL = Duration.ofHours(WINDOW_HOURS + 1);
    private static final Duration TOP_TTL = Duration.ofMinutes(1);

    private final StringRedisTemplate stringRedisTemplate;

    public void increase(String keyword, LocalDateTime now) {
        String key = key(now);
        stringRedisTemplate.opsForZSet().incrementScore(key, keyword, 1);
        stringRedisTemplate.expire(key, TTL);
    }

    // 전체 검색어를 가져오지 않게 Redis에서 합쳐 두고 앞에서 limit개만 읽음
    // 점수에 -1을 곱해 두면 앞에서부터 읽을 때 많은 순, 같은 횟수면 검색어 순이 됨
    public List<TypedTuple<String>> findTop(LocalDateTime now, int limit) {
        List<String> keys = IntStream.range(0, WINDOW_HOURS).mapToObj(hour -> key(now.minusHours(hour))).toList();
        stringRedisTemplate.opsForZSet().unionAndStore(keys.get(0), keys.subList(1, keys.size()), TOP_KEY,
                Aggregate.SUM, Weights.fromSetCount(WINDOW_HOURS).multiply(-1));
        stringRedisTemplate.expire(TOP_KEY, TOP_TTL);
        return stringRedisTemplate.opsForZSet().rangeWithScores(TOP_KEY, 0, limit - 1L).stream()
                .map(tuple -> TypedTuple.of(tuple.getValue(), -tuple.getScore()))
                .toList();
    }

    private String key(LocalDateTime time) {
        return KEY_PREFIX + time.format(HOUR);
    }
}