package com.server.zelo.domain.user.service;

import com.server.zelo.common.auth.JwtUtil;
import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.user.dto.request.LoginRequest;
import com.server.zelo.domain.user.dto.response.LoginResult;
import com.server.zelo.domain.user.entity.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class UserCommandServiceTest {

    @InjectMocks
    private UserCommandService userCommandService;

    @Mock
    private JwtUtil jwtUtil;

    @Mock
    private UserService userService;

    @Mock
    private UserAddressService userAddressService;

    @Mock
    private RefreshTokenService refreshTokenService;

    @Test
    void 로그인하면_토큰을_발급하고_리프레시_토큰을_저장한다() {
        // given
        LoginRequest request = new LoginRequest("jieun@example.com", "DemoPass!2026");
        User user = User.of("jieun@example.com", "encodedPassword", "지은", "010-1234-5678");
        ReflectionTestUtils.setField(user, "id", 1L);
        given(userService.getUserByEmailAndPassword("jieun@example.com", "DemoPass!2026")).willReturn(user);
        given(jwtUtil.createAccessToken(1L)).willReturn("accessToken");
        given(jwtUtil.createRefreshToken(1L)).willReturn("refreshToken");

        // when
        LoginResult result = userCommandService.login(request);

        // then
        assertThat(result.accessToken()).isEqualTo("accessToken");
        assertThat(result.refreshToken()).isEqualTo("refreshToken");
        then(refreshTokenService).should().saveRefreshToken(1L, "refreshToken");
    }

    @Test
    void 로그인에_실패하면_토큰을_저장하지_않는다() {
        // given
        LoginRequest request = new LoginRequest("jieun@example.com", "Wrong1!");
        given(userService.getUserByEmailAndPassword("jieun@example.com", "Wrong1!"))
                .willThrow(new BusinessException(ErrorCode.INVALID_CREDENTIALS));

        // when & then
        assertThatThrownBy(() -> userCommandService.login(request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS);
        then(refreshTokenService).should(never()).saveRefreshToken(anyLong(), anyString());
    }

    @Test
    void 유효한_리프레시_토큰이면_토큰의_유저_id를_반환한다() {
        // given
        given(jwtUtil.validateToken("refreshToken")).willReturn(true);
        given(jwtUtil.isRefreshToken("refreshToken")).willReturn(true);
        given(jwtUtil.getUserId("refreshToken")).willReturn(1L);

        // when
        Long userId = userCommandService.verifyTokenAndGetUserId("refreshToken");

        // then
        assertThat(userId).isEqualTo(1L);
    }

    @Test
    void 위조되었거나_만료되었거나_비어있는_토큰이면_INVALID_TOKEN_예외가_발생한다() {
        // given
        given(jwtUtil.validateToken("invalidToken")).willReturn(false);

        // when & then
        assertThatThrownBy(() -> userCommandService.verifyTokenAndGetUserId("invalidToken"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_TOKEN);
        then(jwtUtil).should(never()).getUserId(anyString());
    }

    @Test
    void 액세스_토큰으로는_재발급할_수_없다() {
        // given
        given(jwtUtil.validateToken("accessToken")).willReturn(true);
        given(jwtUtil.isRefreshToken("accessToken")).willReturn(false);

        // when & then
        assertThatThrownBy(() -> userCommandService.verifyTokenAndGetUserId("accessToken"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_TOKEN);
        then(jwtUtil).should(never()).getUserId(anyString());
    }

    @Test
    void 재발급하면_새_토큰_둘을_발급하고_받은_리프레시_토큰과_함께_교체를_요청한다() {
        // given
        given(jwtUtil.createAccessToken(1L)).willReturn("newAccessToken");
        given(jwtUtil.createRefreshToken(1L)).willReturn("newRefreshToken");

        // when
        LoginResult result = userCommandService.reissue(1L, "oldRefreshToken");

        // then
        assertThat(result.accessToken()).isEqualTo("newAccessToken");
        assertThat(result.refreshToken()).isEqualTo("newRefreshToken");
        // 새 토큰이 아니라 사용자가 보낸 토큰으로 일치 여부를 확인해야 함
        then(refreshTokenService).should().rotateToken(1L, "oldRefreshToken", "newRefreshToken");
    }
}
