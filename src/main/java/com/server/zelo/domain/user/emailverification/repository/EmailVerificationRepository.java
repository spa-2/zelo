package com.server.zelo.domain.user.emailverification.repository;

import com.server.zelo.common.util.HashUtil;
import com.server.zelo.domain.user.emailverification.dto.CodeValidationResult;
import com.server.zelo.domain.user.emailverification.entity.EmailVerificationPurpose;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class EmailVerificationRepository {

    private static final String EMAIL_CODE = "EMAIL_CODE:";
    private static final String EMAIL_TICKET = "EMAIL_TICKET:";
    private static final String EMAIL_COOLDOWN = "EMAIL_COOLDOWN:";
    private static final String EMAIL_IP_COUNT = "EMAIL_IP_COUNT:";
    private static final String EMAIL_SEND_COUNT = "EMAIL_SEND_COUNT:";
    private static final String EMAIL_CODE_FAILED = "EMAIL_CODE_FAILED:";
    private static final String EMAIL_PASSWORD_RESET_COUNT = "EMAIL_PASSWORD_RESET_COUNT:";

    // 이메일별, ip별 카운터가 같은 방식(윈도우 동안 세다가 초과하면 차단 시간으로 전환)이라 스크립트를 같이 쓰고 키만 다르게 한다
    private static final RedisScript<Long> COUNT_SCRIPT = new DefaultRedisScript<>("""
            -- 횟수 증가
            local count = redis.call('INCR', KEYS[1])

            -- 첫 요청, 일반 윈도우 TTL 설정
            if count == 1 then
                redis.call('EXPIRE', KEYS[1], ARGV[1])
            -- 제한 초과가 된 그 순간에만 차단 TTL로 변경
            elseif count == tonumber(ARGV[2]) + 1 then
                redis.call('EXPIRE', KEYS[1], ARGV[3])
            end

            -- 현재 횟수 반환
            return count
            """, Long.class);

    private static final RedisScript<Long> SAVE_CODE_SCRIPT = new DefaultRedisScript<>("""
            -- 새 코드가 이전 코드의 실패 횟수를 물려받지 않도록 같이 지운다
            redis.call('DEL', KEYS[2])
            redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])
            return 1
            """, Long.class);

    private static final RedisScript<Long> VALID_SCRIPT = new DefaultRedisScript<>("""
            -- 반환값: 1 일치, 0 코드 없음/만료, 2 불일치, -1 실패 횟수 초과(코드 폐기)
            local stored = redis.call('GET', KEYS[1])

            -- 코드가 없거나 만료
            if not stored then
                return 0
            end

            -- 일치하면 코드와 실패 횟수를 지운다(같은 코드로 다시 확인할 수 없게)
            if stored == ARGV[1] then
                redis.call('DEL', KEYS[1], KEYS[2])
                return 1
            end

            -- 틀리면 실패 횟수 증가, 처음 틀렸을 때만 TTL 설정
            local fails = redis.call('INCR', KEYS[2])
            if fails == 1 then
                redis.call('EXPIRE', KEYS[2], ARGV[2])
            end

            -- 횟수를 넘으면 코드를 폐기해서 새로 요청하게 한다
            if fails >= tonumber(ARGV[3]) then
                redis.call('DEL', KEYS[1], KEYS[2])
                return -1
            end

            return 2
            """, Long.class);

    private static final RedisScript<Long> ROLLBACK_SEND_SCRIPT = new DefaultRedisScript<>("""
                redis.call('DEL', KEYS[1])
                redis.call('DEL', KEYS[2])

                -- 키가 없으면(윈도우 만료) 감소하지 않는다. DECR이 TTL 없는 -1 키를 만들기 때문
                -- Lua에서 GET은 문자열 또는 false라서 숫자로 바꿔서 비교한다
                local count = tonumber(redis.call('GET', KEYS[3]))
                if count then
                    if count <= 1 then
                        -- 0이 되면 지워서 다음 요청이 새 윈도우로 시작하게 한다
                        redis.call('DEL', KEYS[3])
                    else
                        redis.call('DECR', KEYS[3])
                    end
                end

                return 1
            """, Long.class);

    private final HashUtil hashUtil;
    private final StringRedisTemplate stringRedisTemplate;

    // 동일 ip에서 메일 요청이 많으면 플러딩 공격으로 의심(학교, 공공 wifi 등을 고려하여 한도는 넉넉히 잡음)
    public long increaseIpCount(String remoteAddr, int maxCount, Duration window, Duration block) {
        return increaseCount(ipKey(remoteAddr), maxCount, window, block);
    }

    // 한 이메일 주소로 보내는 횟수(ip와 무관하게 세어서 ip를 바꿔가며 한 사람의 메일함을 공격하는 것을 방지)
    public long increaseSendCount(String email, int maxCount, Duration window, Duration block) {
        return increaseCount(sendKey(email), maxCount, window, block);
    }

    // 동일한 이메일에는 cooldown 동안 1번만 보냄(목적별로 분리), 선점에 성공하면 true
    public boolean acquireCooldown(String email, EmailVerificationPurpose purpose, Duration cooldown) {
        String key = cooldownKey(purpose, email);
        // Set nx ex : 키가 없을때만 저장
        return Boolean.TRUE.equals(stringRedisTemplate.opsForValue().setIfAbsent(key, "1", cooldown));
    }

    // 코드를 저장하는데 만약 이전에 연이은 코드검증 실패로 코드를 재발급을 받는 상황이라면 이전 실패 기록은 지워준다
    public void saveCode(String email, EmailVerificationPurpose purpose, String code, Duration ttl) {
        stringRedisTemplate.execute(
                SAVE_CODE_SCRIPT,
                List.of(codeKey(purpose, email), failedKey(purpose, email)), // key1, key2
                hash(email + ":" + code), // argv1 (코드 원본은 이메일 조합을 해시 처리)
                String.valueOf(ttl.toSeconds())
        );
    }

    // 저장할 때와 같은 방식으로 해시해서 비교한다(해시 방식은 저장소만 알게 함), 실패 횟수는 failTtl 동안만 센다
    public CodeValidationResult validateCode(
            String email, EmailVerificationPurpose purpose, String code, int maxFailures, Duration failTtl) {
        Long result = stringRedisTemplate.execute(
                VALID_SCRIPT,
                List.of(codeKey(purpose, email), failedKey(purpose, email)), // key 1, 2
                hash(email + ":" + code), // argv 1
                String.valueOf(failTtl.toSeconds()), // argv 2
                String.valueOf(maxFailures) // argv 3
        );
        return switch (result.intValue()) {
            case 1 -> CodeValidationResult.SUCCESS;
            case 0 -> CodeValidationResult.EXPIRED;
            case -1 -> CodeValidationResult.TOO_MANY_FAILURES;
            default -> CodeValidationResult.MISMATCH;
        };
    }

    // 가입 요청에서 이메일 인증을 증명하는 티켓, 값에 목적과 이메일을 저장해서 사용할 때 요청과 같은지 비교할 수 있게 한다
    public void saveTicket(String ticket, EmailVerificationPurpose purpose, String email, Duration ttl) {
        stringRedisTemplate.opsForValue().set(ticketKey(ticket), purpose + ":" + hash(email), ttl);
    }

    // 장애시 다시 시도할 수 있게 발급한 코드와 쿨다운을 모두 제거하고 이메일별 발송 횟수를 1 감소 또는 삭제한다.
    public void rollbackSend(String email, EmailVerificationPurpose purpose) {
        stringRedisTemplate.execute(
                ROLLBACK_SEND_SCRIPT,
                List.of(cooldownKey(purpose, email),
                        codeKey(purpose, email),
                        sendKey(email)
                )
        );
    }

    public boolean consumeTicket(String ticket, EmailVerificationPurpose purpose, String email) {
        String ticketKey = ticketKey(ticket);
        String ticketValue = purpose + ":" + hash(email);
        String storedValue = stringRedisTemplate.opsForValue().getAndDelete(ticketKey);
        return ticketValue.equals(storedValue);
    }

    // 비밀번호 재설정은 가입 여부를 응답으로 알려 주므로, 메일을 보내지 않는 요청(없는 이메일)도 세어서 이메일을 하나씩 대입하는 조회를 막는다
    // 메일 발송용 ip 카운터와는 한도가 달라서 키를 분리한다
    public long increasePasswordResetCount(String remoteAddr, int maxCount, Duration window, Duration block) {
        return increaseCount(passwordResetKey(remoteAddr), maxCount, window, block);
    }

    private long increaseCount(String key, int maxCount, Duration window, Duration block) {
        return stringRedisTemplate.execute(
                COUNT_SCRIPT,
                List.of(key),
                String.valueOf(window.toSeconds()), // argv 1
                String.valueOf(maxCount), // argv 2
                String.valueOf(block.toSeconds()) // argv 3
        );
    }

    // 이메일 인증 코드
    private String codeKey(EmailVerificationPurpose purpose, String email) {
        return EMAIL_CODE + purpose + ":" + hash(email);
    }

    // 인증 코드 실패
    private String failedKey(EmailVerificationPurpose purpose, String email) {
        return EMAIL_CODE_FAILED + purpose + ":" + hash(email);
    }

    // 메일 전송 쿨다운
    private String cooldownKey(EmailVerificationPurpose purpose, String email) {
        return EMAIL_COOLDOWN + purpose + ":" + hash(email);
    }

    // 메일 전송 횟수
    private String sendKey(String email){
        return EMAIL_SEND_COUNT + hash(email);
    }

    // ip당 비밀번호 재설정 요청 횟수
    private String passwordResetKey(String remoteAddr) {
        return EMAIL_PASSWORD_RESET_COUNT + hash(remoteAddr);
    }

    // ip당 메일 전송 횟수
    private String ipKey(String remoteAddr){
        return EMAIL_IP_COUNT + hash(remoteAddr);
    }

    // 티켓 저장
    private String ticketKey(String ticket){
        return EMAIL_TICKET + hash(ticket);
    }

    private String hash(String value) {
        return hashUtil.hash(value);
    }
}
