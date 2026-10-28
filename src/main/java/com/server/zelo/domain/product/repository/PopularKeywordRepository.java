package com.server.zelo.domain.product.repository;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.stereotype.Repository;

// 검색어를 한 시간 단위 ZSET(search:keyword:2026102814)에 세고, 최근 24시간치를 합쳐서 순위를 냄
// 시간대별로 키를 나눠서 TTL로 오래된 검색어가 저절로 빠지게 함
@Repository
@RequiredArgsConstructor
public class PopularKeywordRepository {

    private static final String KEY_PREFIX = "search:keyword:";
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("yyyyMMddHH");
    private static final int WINDOW_HOURS = 24;
    // 합칠 때 24시간 전 키까지 남아 있게 한 시간 여유를 둠
    private static final Duration TTL = Duration.ofHours(WINDOW_HOURS + 1);

    private final StringRedisTemplate stringRedisTemplate;

    public void increase(String keyword, LocalDateTime now) {
        String key = key(now);
        stringRedisTemplate.opsForZSet().incrementScore(key, keyword, 1);
        stringRedisTemplate.expire(key, TTL);
    }

    // 횟수가 같으면 검색어 순으로 정렬해서 순위가 매번 같게 나오게 함
    public List<TypedTuple<String>> findTop(LocalDateTime now, int limit) {
        List<String> keys = IntStream.range(0, WINDOW_HOURS).mapToObj(hour -> key(now.minusHours(hour))).toList();
        return stringRedisTemplate.opsForZSet().unionWithScores(keys.get(0), keys.subList(1, keys.size())).stream()
                .sorted(Comparator.comparing((TypedTuple<String> tuple) -> tuple.getScore()).reversed()
                        .thenComparing(TypedTuple::getValue))
                .limit(limit)
                .toList();
    }

    private String key(LocalDateTime time) {
        return KEY_PREFIX + time.format(HOUR);
    }
}