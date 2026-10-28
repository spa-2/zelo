package com.server.zelo.global.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.server.zelo.common.bean.PasswordEncoder;
import com.server.zelo.domain.user.entity.User;
import com.server.zelo.domain.user.entity.UserRole;
import com.server.zelo.domain.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AdminSeedLoaderTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private AdminSeedLoader adminSeedLoader;

    @Test
    void 환경변수로_받은_이메일로_관리자를_만든다() {
        // given
        setAdmin("Admin@Zelo.com", "admin1234!");
        given(userRepository.existsByEmail("admin@zelo.com")).willReturn(false);
        given(passwordEncoder.encode("admin1234!")).willReturn("encoded");

        // when
        adminSeedLoader.run(null);

        // then
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        then(userRepository).should().save(captor.capture());
        assertThat(captor.getValue().getEmail()).isEqualTo("admin@zelo.com");
        assertThat(captor.getValue().getPassword()).isEqualTo("encoded");
        assertThat(captor.getValue().getRole()).isEqualTo(UserRole.ADMIN);
    }

    @Test
    void 같은_이메일_계정이_있으면_만들지_않는다() {
        // given
        setAdmin("admin@zelo.com", "admin1234!");
        given(userRepository.existsByEmail("admin@zelo.com")).willReturn(true);

        // when
        adminSeedLoader.run(null);

        // then
        then(userRepository).should(never()).save(any());
    }

    @Test
    void 환경변수가_없으면_만들지_않는다() {
        // given
        setAdmin("", "");

        // when
        adminSeedLoader.run(null);

        // then
        then(userRepository).shouldHaveNoInteractions();
    }

    private void setAdmin(String email, String password) {
        ReflectionTestUtils.setField(adminSeedLoader, "email", email);
        ReflectionTestUtils.setField(adminSeedLoader, "password", password);
    }
}