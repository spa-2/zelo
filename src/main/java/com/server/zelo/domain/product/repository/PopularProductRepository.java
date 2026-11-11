package com.server.zelo.domain.product.repository;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.connection.zset.Aggregate;
import org.springframework.data.redis.connection.zset.Weights;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.stereotype.Repository;

// 판매 수량을 하루 단위 ZSET(product:sales:20261029)에 세고, 최근 7일치를 합쳐서 순위를 냄
// 누적 판매량은 DB에 있어서 Redis는 "요즘 많이 팔리는 상품"만 봄. 오래된 날짜 키는 TTL로 저절로 빠짐
@Repository
@RequiredArgsConstructor
public class PopularProductRepository {

    private static final String KEY_PREFIX = "product:sales:";
    private static final String TOP_KEY = "product:sales:top";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int WINDOW_DAYS = 7;
    // 합칠 때 7일 전 키까지 남아 있게 하루 여유를 둠
    private static final Duration TTL = Duration.ofDays(WINDOW_DAYS + 1);
    // 조회마다 7개 키를 다시 합치지 않게 합친 결과를 1분 동안 씀
    private static final Duration TOP_TTL = Duration.ofMinutes(1);

    private final StringRedisTemplate stringRedisTemplate;

    // 취소는 quantity를 음수로 넘겨서 취소한 날 키에서 뺌. 7일 합계에서 판매랑 상쇄됨
    public void increase(Long productId, int quantity, LocalDate today) {
        String key = key(today);
        stringRedisTemplate.opsForZSet().incrementScore(key, productId.toString(), quantity);
        stringRedisTemplate.expire(key, TTL);
    }

    // 점수에 -1을 곱해 두면 앞에서부터 읽을 때 많이 팔린 순이 됨. -1 이하만 읽어서 합이 0 이하인 상품은 뺌
    public List<TypedTuple<String>> findTop(LocalDate today, int limit) {
        if (!Boolean.TRUE.equals(stringRedisTemplate.hasKey(TOP_KEY))) {
            List<String> keys = IntStream.range(0, WINDOW_DAYS).mapToObj(day -> key(today.minusDays(day))).toList();
            stringRedisTemplate.opsForZSet().unionAndStore(keys.get(0), keys.subList(1, keys.size()), TOP_KEY,
                    Aggregate.SUM, Weights.fromSetCount(WINDOW_DAYS).multiply(-1));
            stringRedisTemplate.expire(TOP_KEY, TOP_TTL);
        }
        return stringRedisTemplate.opsForZSet()
                .rangeByScoreWithScores(TOP_KEY, Double.NEGATIVE_INFINITY, -1, 0, limit).stream()
                .map(tuple -> TypedTuple.of(tuple.getValue(), -tuple.getScore()))
                .toList();
    }

    private String key(LocalDate day) {
        return KEY_PREFIX + day.format(DAY);
    }
}