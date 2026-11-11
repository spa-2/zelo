package com.server.zelo.global.seed;

import com.server.zelo.common.bean.PasswordEncoder;
import com.server.zelo.domain.user.entity.User;
import com.server.zelo.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// 관리자 가입 API가 없어서 시드로 관리자 계정을 하나 만듦. ZELO_SEED_ENABLED=true 일 때만 돎
// 이메일·비번은 코드에 안 남기려고 ZELO_SEED_ADMIN_EMAIL, ZELO_SEED_ADMIN_PASSWORD 환경변수로 받음
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "zelo.seed.enabled", havingValue = "true")
public class AdminSeedLoader implements ApplicationRunner {

    private static final String ADMIN_NAME = "관리자";
    private static final String ADMIN_PHONE_NUMBER = "010-0000-0000";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${zelo.seed.admin.email:}")
    private String email;

    @Value("${zelo.seed.admin.password:}")
    private String password;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (email.isBlank() || password.isBlank()) {
            log.info("관리자 이메일·비번 환경변수가 없어서 관리자 시드 건너뜀");
            return;
        }
        String normalizedEmail = User.normalizeEmail(email);
        if (userRepository.existsByEmail(normalizedEmail)) {
            log.info("같은 이메일 계정이 이미 있어서 관리자 시드 건너뜀");
            return;
        }
        userRepository.save(User.ofAdmin(normalizedEmail, passwordEncoder.encode(password), ADMIN_NAME, ADMIN_PHONE_NUMBER));
        log.info("관리자 계정 생성 완료");
    }
}