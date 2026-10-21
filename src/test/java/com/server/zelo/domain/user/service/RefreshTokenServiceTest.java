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
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.BDDMockito.then;

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
}
