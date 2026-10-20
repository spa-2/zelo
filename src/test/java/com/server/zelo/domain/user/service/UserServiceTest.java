package com.server.zelo.domain.user.service;

import com.server.zelo.common.bean.PasswordEncoder;
import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.user.entity.User;
import com.server.zelo.domain.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @InjectMocks
    private UserService userService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Test
    void 이메일이_중복되면_예외가_발생한다() {
        // given
        given(userRepository.existsByEmail("test@example.com")).willReturn(true);

        // when & then
        assertThatThrownBy(() -> userService.createUser("test@example.com", "Password1!", "홍길동", "010-1234-5678"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.DUPLICATE_EMAIL);
        then(userRepository).should(never()).save(any(User.class));
    }

    @Test
    void 비밀번호를_암호화해서_저장한다() {
        // given
        given(userRepository.existsByEmail("test@example.com")).willReturn(false);
        given(passwordEncoder.encode("Password1!")).willReturn("encodedPassword");
        given(userRepository.save(any(User.class))).willAnswer(invocation -> invocation.getArgument(0));

        // when
        User user = userService.createUser("test@example.com", "Password1!", "홍길동", "010-1234-5678");

        // then
        assertThat(user.getPassword()).isEqualTo("encodedPassword");
        assertThat(user.getEmail()).isEqualTo("test@example.com");
        assertThat(user.getName()).isEqualTo("홍길동");
        assertThat(user.getPhoneNumber()).isEqualTo("010-1234-5678");
    }

    @Test
    void 대소문자만_다른_이메일도_중복으로_판단한다() {
        // given
        given(userRepository.existsByEmail("test@example.com")).willReturn(true);

        // when & then
        assertThatThrownBy(() -> userService.createUser("TEST@Example.com", "Password1!", "홍길동", "010-1234-5678"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.DUPLICATE_EMAIL);
        then(userRepository).should(never()).save(any(User.class));
    }

    @Test
    void 대문자가_섞인_이메일은_소문자로_저장한다() {
        // given
        given(userRepository.existsByEmail("test@example.com")).willReturn(false);
        given(passwordEncoder.encode("Password1!")).willReturn("encodedPassword");
        given(userRepository.save(any(User.class))).willAnswer(invocation -> invocation.getArgument(0));

        // when
        User user = userService.createUser("TEST@Example.com", "Password1!", "홍길동", "010-1234-5678");

        // then
        assertThat(user.getEmail()).isEqualTo("test@example.com");
    }

    @Test
    void 가입되지_않은_이메일로_로그인하면_예외가_발생한다() {
        // given
        given(userRepository.findByEmail("none@example.com")).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> userService.getUserByEmailAndPassword("none@example.com", "Password1!"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS);
    }

    @Test
    void 비밀번호가_틀리면_예외가_발생한다() {
        // given
        User user = User.of("test@example.com", "encodedPassword", "홍길동", "010-1234-5678");
        given(userRepository.findByEmail("test@example.com")).willReturn(Optional.of(user));
        given(passwordEncoder.matches("Wrong1!", "encodedPassword")).willReturn(false);

        // when & then
        assertThatThrownBy(() -> userService.getUserByEmailAndPassword("test@example.com", "Wrong1!"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS);
    }

    @Test
    void 이메일과_비밀번호가_맞으면_유저를_반환한다() {
        // given
        User user = User.of("test@example.com", "encodedPassword", "홍길동", "010-1234-5678");
        given(userRepository.findByEmail("test@example.com")).willReturn(Optional.of(user));
        given(passwordEncoder.matches("Password1!", "encodedPassword")).willReturn(true);

        // when
        User result = userService.getUserByEmailAndPassword("TEST@example.com", "Password1!");

        // then
        assertThat(result).isSameAs(user);
    }
}
