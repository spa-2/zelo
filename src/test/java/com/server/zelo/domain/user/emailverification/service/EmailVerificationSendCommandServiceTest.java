package com.server.zelo.domain.user.emailverification.service;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.common.exception.RateLimitExceededException;
import com.server.zelo.domain.user.emailverification.entity.EmailVerificationPurpose;
import com.server.zelo.domain.user.entity.User;
import com.server.zelo.domain.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class EmailVerificationSendCommandServiceTest {

    private static final String EMAIL = "jieun@example.com";
    private static final String REMOTE_ADDR = "127.0.0.1";

    @InjectMocks
    private EmailVerificationSendCommandService emailVerificationSendCommandService;

    @Mock
    private UserService userService;

    @Mock
    private EmailVerificationCommandService emailVerificationCommandService;

    @Test
    void 회원가입_목적이면_회원을_조회하지_않고_바로_전송한다() {
        // when
        emailVerificationSendCommandService.sendEmail(EMAIL, EmailVerificationPurpose.SIGNUP, REMOTE_ADDR);

        // then 가입 대상은 아직 회원이 아니라서 조회하면 항상 실패함
        then(userService).shouldHaveNoInteractions();
        // 재설정용 한도를 가입 요청이 소모하면 안 됨
        then(emailVerificationCommandService).should(never()).validatePasswordResetLimit(anyString());
        then(emailVerificationCommandService).should()
                .sendEmail(EMAIL, EmailVerificationPurpose.SIGNUP, REMOTE_ADDR);
    }

    @Test
    void 비밀번호_찾기_목적이고_비밀번호가_있는_회원이면_전송한다() {
        // given
        User user = User.of(EMAIL, "encodedPassword", "지은", "010-1234-5678");
        given(userService.getUserByEmail(EMAIL)).willReturn(user);

        // when
        emailVerificationSendCommandService.sendEmail(EMAIL, EmailVerificationPurpose.PASSWORD_RESET, REMOTE_ADDR);

        // then
        then(emailVerificationCommandService).should()
                .sendEmail(EMAIL, EmailVerificationPurpose.PASSWORD_RESET, REMOTE_ADDR);
    }

    @Test
    void 비밀번호_찾기_목적인데_가입되지_않은_이메일이면_예외가_발생하고_전송하지_않는다() {
        // given
        willThrow(new BusinessException(ErrorCode.MEMBER_NOT_FOUND))
                .given(userService).getUserByEmail(EMAIL);

        // when & then
        assertThatThrownBy(() ->
                emailVerificationSendCommandService.sendEmail(EMAIL, EmailVerificationPurpose.PASSWORD_RESET, REMOTE_ADDR))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.MEMBER_NOT_FOUND));
        then(emailVerificationCommandService).should(never()).sendEmail(anyString(), any(), anyString());
    }

    @Test
    void 비밀번호_찾기_목적인데_소셜_가입_회원이면_예외가_발생하고_전송하지_않는다() {
        // given
        User socialUser = User.ofSocial(EMAIL, "지은", "010-1234-5678");
        given(userService.getUserByEmail(EMAIL)).willReturn(socialUser);

        // when & then
        assertThatThrownBy(() ->
                emailVerificationSendCommandService.sendEmail(EMAIL, EmailVerificationPurpose.PASSWORD_RESET, REMOTE_ADDR))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.SOCIAL_ACCOUNT_USER));
        then(emailVerificationCommandService).should(never()).sendEmail(anyString(), any(), anyString());
    }

    @Test
    void 비밀번호_찾기_목적이면_가입_여부를_조회하기_전에_요청_횟수를_먼저_센다() {
        // given
        User user = User.of(EMAIL, "encodedPassword", "지은", "010-1234-5678");
        given(userService.getUserByEmail(EMAIL)).willReturn(user);

        // when
        emailVerificationSendCommandService.sendEmail(EMAIL, EmailVerificationPurpose.PASSWORD_RESET, REMOTE_ADDR);

        // then 조회가 먼저면 없는 이메일 대입이 한도 없이 가능해짐
        InOrder inOrder = inOrder(emailVerificationCommandService, userService);
        inOrder.verify(emailVerificationCommandService).validatePasswordResetLimit(REMOTE_ADDR);
        inOrder.verify(userService).getUserByEmail(EMAIL);
        inOrder.verify(emailVerificationCommandService)
                .sendEmail(EMAIL, EmailVerificationPurpose.PASSWORD_RESET, REMOTE_ADDR);
    }

    @Test
    void 비밀번호_재설정_요청_한도를_넘으면_회원을_조회하지_않고_전송하지_않는다() {
        // given
        willThrow(new RateLimitExceededException(ErrorCode.TOO_MANY_PASSWORD_RESET_REQUESTS, 300L))
                .given(emailVerificationCommandService).validatePasswordResetLimit(REMOTE_ADDR);

        // when & then
        assertThatThrownBy(() ->
                emailVerificationSendCommandService.sendEmail(EMAIL, EmailVerificationPurpose.PASSWORD_RESET, REMOTE_ADDR))
                .isInstanceOfSatisfying(RateLimitExceededException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.TOO_MANY_PASSWORD_RESET_REQUESTS));
        then(userService).shouldHaveNoInteractions();
        then(emailVerificationCommandService).should(never()).sendEmail(anyString(), any(), anyString());
    }
}
