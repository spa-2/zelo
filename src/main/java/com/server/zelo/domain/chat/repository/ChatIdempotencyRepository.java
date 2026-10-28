package com.server.zelo.domain.chat.repository;

import com.server.zelo.domain.chat.model.ChatIdempotencyRecord;
import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
@RequiredArgsConstructor
public class ChatIdempotencyRepository {

    // 처리 중 표시는 요청 하나의 최악 시간(LLM 3번, 약 73.5초)보다 길게 둔다. 서버가 처리 중에 죽어도 이 시간이 지나면 풀린다
    public static final Duration PROCESSING_TTL = Duration.ofMinutes(2);
    // 응답을 잃은 화면이 같은 키로 다시 보낼 시간
    public static final Duration DONE_TTL = Duration.ofMinutes(10);

    private static final String KEY_PREFIX = "chat:idempotency:";

    private final JsonMapper jsonMapper;
    private final StringRedisTemplate stringRedisTemplate;

    // 키가 없을 때만 처리 중으로 저장한다(SETNX). 이미 있으면 false
    public boolean saveIfAbsent(Long userId, String idempotencyKey, ChatIdempotencyRecord record) {
        return Boolean.TRUE.equals(stringRedisTemplate.opsForValue()
                .setIfAbsent(key(userId, idempotencyKey), jsonMapper.writeValueAsString(record), PROCESSING_TTL));
    }

    // 처리가 끝난 결과로 덮어쓰고 TTL을 다시 정한다
    public void save(Long userId, String idempotencyKey, ChatIdempotencyRecord record) {
        stringRedisTemplate.opsForValue()
                .set(key(userId, idempotencyKey), jsonMapper.writeValueAsString(record), DONE_TTL);
    }

    public Optional<ChatIdempotencyRecord> find(Long userId, String idempotencyKey) {
        String json = stringRedisTemplate.opsForValue().get(key(userId, idempotencyKey));
        if (json == null) {
            return Optional.empty();
        }
        return Optional.of(jsonMapper.readValue(json, ChatIdempotencyRecord.class));
    }

    public void delete(Long userId, String idempotencyKey) {
        stringRedisTemplate.delete(key(userId, idempotencyKey));
    }

    // 다른 사용자가 같은 UUID를 보내도 섞이지 않게 사용자 ID를 넣는다
    private String key(Long userId, String idempotencyKey) {
        return KEY_PREFIX + userId + ":" + idempotencyKey;
    }
}
