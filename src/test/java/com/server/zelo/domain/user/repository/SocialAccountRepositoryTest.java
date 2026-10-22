package com.server.zelo.domain.user.repository;

import com.server.zelo.domain.user.entity.SocialAccount;
import com.server.zelo.domain.user.entity.SocialProvider;
import com.server.zelo.domain.user.entity.User;
import com.server.zelo.global.config.AuditingConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 유니크 제약과 컬럼 매핑은 실제 PostgreSQL에서만 확인할 수 있어서 내장 DB 대신 실제 DB를 쓴다 (테스트가 끝나면 롤백됨)
@DataJpaTest
@ActiveProfiles("test")
@Import(AuditingConfig.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class SocialAccountRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SocialAccountRepository socialAccountRepository;

    @Test
    void 비밀번호가_없는_소셜_사용자와_소셜_계정을_저장한다() {
        // given
        User user = userRepository.save(User.ofSocial(uniqueEmail(), "지은", "010-1234-5678"));

        // when
        SocialAccount saved = socialAccountRepository.saveAndFlush(
                SocialAccount.of(user, SocialProvider.GOOGLE, uniqueProviderId()));

        // then
        // users.password가 NOT NULL이면 위 저장에서 실패함 (기존 DB는 ALTER TABLE로 제약을 풀어야 함)
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUser().getPassword()).isNull();
    }

    @Test
    void 같은_제공자의_같은_소셜_계정은_다른_사용자에게_연결할_수_없다() {
        // given
        String providerId = uniqueProviderId();
        User first = userRepository.save(User.ofSocial(uniqueEmail(), "지은", "010-1234-5678"));
        User second = userRepository.save(User.ofSocial(uniqueEmail(), "민수", "010-2345-6789"));
        socialAccountRepository.saveAndFlush(SocialAccount.of(first, SocialProvider.GOOGLE, providerId));

        // when & then
        assertThatThrownBy(() ->
                socialAccountRepository.saveAndFlush(SocialAccount.of(second, SocialProvider.GOOGLE, providerId)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 한_사용자는_같은_제공자를_두_번_연결할_수_없다() {
        // given
        User user = userRepository.save(User.ofSocial(uniqueEmail(), "지은", "010-1234-5678"));
        socialAccountRepository.saveAndFlush(SocialAccount.of(user, SocialProvider.GOOGLE, uniqueProviderId()));

        // when & then
        assertThatThrownBy(() ->
                socialAccountRepository.saveAndFlush(SocialAccount.of(user, SocialProvider.GOOGLE, uniqueProviderId())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 서로_다른_사용자는_서로_다른_소셜_계정을_연결할_수_있다() {
        // given
        User first = userRepository.save(User.ofSocial(uniqueEmail(), "지은", "010-1234-5678"));
        User second = userRepository.save(User.ofSocial(uniqueEmail(), "민수", "010-2345-6789"));

        // when
        socialAccountRepository.saveAndFlush(SocialAccount.of(first, SocialProvider.GOOGLE, uniqueProviderId()));
        SocialAccount saved = socialAccountRepository.saveAndFlush(
                SocialAccount.of(second, SocialProvider.GOOGLE, uniqueProviderId()));

        // then
        assertThat(saved.getId()).isNotNull();
    }

    // 로컬 DB에 쌓인 데이터와 겹치지 않도록 매번 새로운 값을 쓴다 (이메일은 최대 50자)
    private String uniqueEmail() {
        return "st-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    }

    private String uniqueProviderId() {
        return UUID.randomUUID().toString();
    }
}
