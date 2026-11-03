package com.server.zelo.global.seed;

import com.server.zelo.common.bean.PasswordEncoder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

// 부하 테스트용 더미 사용자 데이터. 사용자 3만 명(id 1~30000)과 소셜 계정, 배송지를 JdbcTemplate 배치로 넣음
// 기존 시드(zelo.seed.enabled)가 운영 배포에서 켜질 수 있어서 별도 속성(ZELO_SEED_LOAD_TEST_ENABLED=true)일 때만 돌게 분리함
// 사용자 id를 직접 지정하므로 끝나면 시퀀스를 맞춰 줌. 안 맞추면 이후 실제 가입에서 PK가 중복됨
// 관리자 시드(AdminSeedLoader)보다 먼저 돌아야 더미 사용자가 id 1부터 들어가고, 관리자는 id 30001이 됨 (순서 지정이 없는 러너는 가장 마지막에 돎)
@Slf4j
@Order(1)
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "zelo.seed.load-test.enabled", havingValue = "true")
public class LoadTestUserSeedLoader implements ApplicationRunner {

    private static final int USER_COUNT = 30_000;
    // 이메일 사용자 70%(id 1~21000), 소셜 사용자 30%(id 21001~30000)
    private static final int EMAIL_USER_COUNT = 21_000;
    private static final int BATCH_SIZE = 1_000;
    // k6가 로그인에 쓰는 공통 비밀번호. BCrypt는 느려서 한 번만 해시해 모든 사용자에게 같은 값을 넣음
    private static final String COMMON_PASSWORD = "Zelo1234!";
    private static final String[] ADDRESS_PLACES = {"집", "회사", "기타"};

    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Integer existingCount = jdbcTemplate.queryForObject(
                "select count(*) from users where id between 1 and ?", Integer.class, USER_COUNT);
        if (existingCount != null && existingCount > 0) {
            log.info("id 1~{} 범위에 사용자가 이미 있어서 부하 테스트 더미 데이터 건너뜀", USER_COUNT);
            return;
        }

        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        insertUsers(passwordEncoder.encode(COMMON_PASSWORD), now);
        insertSocialAccounts(now);
        insertAddresses(now);
        resetUserIdSequence();
        // 통계가 없으면 실행 계획이 실제와 달라질 수 있어서 넣은 뒤 갱신함
        jdbcTemplate.execute("analyze users");
        jdbcTemplate.execute("analyze social_accounts");
        jdbcTemplate.execute("analyze user_addresses");
        log.info("부하 테스트 더미 데이터 생성 완료 (사용자 {}명)", USER_COUNT);
    }

    private void insertUsers(String encodedPassword, Timestamp now) {
        String sql = "insert into users (id, email, password, name, phone_number, role, created_at, updated_at) "
                + "values (?, ?, ?, ?, ?, 'BASIC', ?, ?)";
        List<Integer> userIds = IntStream.rangeClosed(1, USER_COUNT).boxed().toList();

        jdbcTemplate.batchUpdate(sql, userIds, BATCH_SIZE, (ps, id) -> {
            boolean emailUser = isEmailUser(id);
            ps.setLong(1, id);
            ps.setString(2, emailUser ? "load" + id + "@test.com" : socialCode(id) + "@fake.example.com");
            if (emailUser) {
                ps.setString(3, encodedPassword);
            } else {
                // 소셜 가입 사용자는 비밀번호가 없음
                ps.setNull(3, Types.VARCHAR);
            }
            ps.setString(4, emailUser ? "부하유저" + id : socialCode(id));
            ps.setString(5, phoneNumber(id));
            ps.setTimestamp(6, now);
            ps.setTimestamp(7, now);
        });
    }

    private void insertSocialAccounts(Timestamp now) {
        // FakeSocialLoginClient가 GOOGLE 하나로 응답하고 code를 providerId로 쓰므로 모두 GOOGLE로 넣음
        String sql = "insert into social_accounts (user_id, provider, provider_id, created_at, updated_at) "
                + "values (?, 'GOOGLE', ?, ?, ?)";
        List<Integer> socialUserIds = IntStream.rangeClosed(EMAIL_USER_COUNT + 1, USER_COUNT).boxed().toList();

        jdbcTemplate.batchUpdate(sql, socialUserIds, BATCH_SIZE, (ps, id) -> {
            ps.setLong(1, id);
            ps.setString(2, socialCode(id));
            ps.setTimestamp(3, now);
            ps.setTimestamp(4, now);
        });
    }

    private void insertAddresses(Timestamp now) {
        String sql = "insert into user_addresses (user_id, place, recipient_name, recipient_phone, zip_code, "
                + "address, is_default, created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?)";

        // 사용자마다 1~3개. 첫 번째 배송지만 기본 배송지(사용자당 기본 배송지는 항상 1개여야 함)
        List<int[]> addresses = new ArrayList<>();
        for (int userId = 1; userId <= USER_COUNT; userId++) {
            int addressCount = 1 + userId % 3;
            for (int sequence = 0; sequence < addressCount; sequence++) {
                addresses.add(new int[]{userId, sequence});
            }
        }

        jdbcTemplate.batchUpdate(sql, addresses, BATCH_SIZE, (ps, userAndSequence) -> {
            int userId = userAndSequence[0];
            int sequence = userAndSequence[1];
            ps.setLong(1, userId);
            ps.setString(2, ADDRESS_PLACES[sequence]);
            ps.setString(3, "수령인" + userId);
            ps.setString(4, phoneNumber(userId));
            ps.setString(5, String.format("%05d", 10_000 + userId % 90_000));
            ps.setString(6, "서울특별시 부하구 테스트로 " + userId + "번길 " + (sequence + 1));
            ps.setBoolean(7, sequence == 0);
            ps.setTimestamp(8, now);
            ps.setTimestamp(9, now);
        });
    }

    private void resetUserIdSequence() {
        jdbcTemplate.queryForObject(
                "select setval(pg_get_serial_sequence('users', 'id'), (select max(id) from users))", Long.class);
    }

    private boolean isEmailUser(int userId) {
        return userId <= EMAIL_USER_COUNT;
    }

    // k6가 소셜 로그인 code로 그대로 보내면 같은 사용자로 로그인됨 (fake는 code를 providerId로 사용)
    private String socialCode(int userId) {
        return "fake-load-" + userId;
    }

    private String phoneNumber(int userId) {
        return String.format("010-%04d-%04d", userId / 10_000, userId % 10_000);
    }
}
