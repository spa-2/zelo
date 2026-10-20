package com.server.zelo.domain.user.service;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.user.repository.RefreshTokenRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    @InjectMocks
    private RefreshTokenService refreshTokenService;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Test
    void 리프레시_토큰을_삭제한다() {
        // when
        refreshTokenService.deleteRefreshToken(1L);

        // then
        then(refreshTokenRepository).should().delete(1L);
    }

    @Test
    void Redis_장애로_삭제에_실패하면_TOKEN_REVOKE_FAILED_예외가_발생한다() {
        // given
        willThrow(new RedisConnectionFailureException("연결 실패")).given(refreshTokenRepository).delete(1L);

        // when & then
        assertThatThrownBy(() -> refreshTokenService.deleteRefreshToken(1L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.TOKEN_REVOKE_FAILED);
    }

    @Test
    void Redis_이외의_예외는_감싸지_않고_그대로_던진다() {
        // given
        willThrow(new IllegalStateException("예상 밖 오류")).given(refreshTokenRepository).delete(1L);

        // when & then
        assertThatThrownBy(() -> refreshTokenService.deleteRefreshToken(1L))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 삭제는_예외_없이_끝나면_성공이다() {
        // when & then
        assertThatCode(() -> refreshTokenService.deleteRefreshToken(1L)).doesNotThrowAnyException();
    }

    @Test
    void 현재_토큰과_일치하면_새_토큰으로_교체한다() {
        // given
        given(refreshTokenRepository.matches(1L, "presented")).willReturn(true);

        // when
        refreshTokenService.rotateToken(1L, "presented", "newToken");

        // then
        then(refreshTokenRepository).should().rotate(1L, "newToken");
        then(refreshTokenRepository).should(never()).delete(anyLong());
    }

    @Test
    void 다른_기기_로그인으로_낡아진_토큰이면_LOGGED_IN_ELSEWHERE_예외가_발생하고_세션은_건드리지_않는다() {
        // given
        given(refreshTokenRepository.matches(1L, "presented")).willReturn(false);
        given(refreshTokenRepository.isSuperseded("presented")).willReturn(true);

        // when & then
        assertThatThrownBy(() -> refreshTokenService.rotateToken(1L, "presented", "newToken"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.LOGGED_IN_ELSEWHERE);
        // 새 기기의 세션을 끊으면 안 되고, 교체도 하지 않음
        then(refreshTokenRepository).should(never()).delete(anyLong());
        then(refreshTokenRepository).should(never()).rotate(anyLong(), anyString());
    }

    @Test
    void 저장된_토큰이_없으면_INVALID_TOKEN_예외가_발생하고_세션을_만들지_않는다() {
        // given
        given(refreshTokenRepository.matches(1L, "presented")).willReturn(false);
        given(refreshTokenRepository.isSuperseded("presented")).willReturn(false);
        given(refreshTokenRepository.exists(1L)).willReturn(false);

        // when & then
        assertThatThrownBy(() -> refreshTokenService.rotateToken(1L, "presented", "newToken"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_TOKEN);
        // 로그아웃한 사용자의 토큰으로 세션이 되살아나면 안 됨
        then(refreshTokenRepository).should(never()).rotate(anyLong(), anyString());
        then(refreshTokenRepository).should(never()).delete(anyLong());
    }

    @Test
    void 교체된_토큰이_재사용되면_TOKEN_REUSED_예외가_발생하고_현재_토큰을_삭제한다() {
        // given
        given(refreshTokenRepository.matches(1L, "presented")).willReturn(false);
        given(refreshTokenRepository.isSuperseded("presented")).willReturn(false);
        given(refreshTokenRepository.exists(1L)).willReturn(true);

        // when & then
        assertThatThrownBy(() -> refreshTokenService.rotateToken(1L, "presented", "newToken"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.TOKEN_REUSED);
        then(refreshTokenRepository).should().delete(1L);
        then(refreshTokenRepository).should(never()).rotate(anyLong(), anyString());
    }
}
