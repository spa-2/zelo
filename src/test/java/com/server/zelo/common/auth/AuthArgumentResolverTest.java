package com.server.zelo.common.auth;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthArgumentResolverTest {

    private final AuthArgumentResolver resolver = new AuthArgumentResolver();

    // 테스트끼리 인증 정보가 섞이지 않게 비움
    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 인증_정보가_있으면_AuthUser를_반환한다() {
        // given
        AuthUser authUser = AuthUser.of(1L);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(authUser, null, AuthorityUtils.NO_AUTHORITIES));

        // when
        Object result = resolver.resolveArgument(null, null, null, null);

        // then
        assertThat(result).isEqualTo(authUser);
    }

    @Test
    void 인증_정보가_없으면_UNAUTHORIZED_예외가_발생한다() {
        // when & then
        assertThatThrownBy(() -> resolver.resolveArgument(null, null, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);
    }

    @Test
    void principal이_AuthUser가_아니면_UNAUTHORIZED_예외가_발생한다() {
        // given
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("anonymousUser", null, AuthorityUtils.NO_AUTHORITIES));

        // when & then
        assertThatThrownBy(() -> resolver.resolveArgument(null, null, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);
    }
}
