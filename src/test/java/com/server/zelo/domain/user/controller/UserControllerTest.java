package com.server.zelo.domain.user.controller;

import com.server.zelo.domain.user.entity.UserRole;
import com.server.zelo.common.auth.AuthUser;
import com.server.zelo.common.auth.RefreshTokenCookieFactory;
import com.server.zelo.common.auth.AuthArgumentResolver;
import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.common.exception.GlobalExceptionHandler;
import com.server.zelo.common.exception.RateLimitExceededException;
import com.server.zelo.domain.user.dto.request.LoginRequest;
import com.server.zelo.domain.user.dto.request.ResetPasswordRequest;
import com.server.zelo.domain.user.dto.response.LoginResult;
import com.server.zelo.domain.user.service.RefreshTokenService;
import com.server.zelo.domain.user.service.UserCommandService;
import com.server.zelo.domain.user.service.UserFacade;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 스프링 컨텍스트 없이 컨트롤러·검증·예외 처리만 묶어서 요청과 응답 형식을 검증한다
@ExtendWith(MockitoExtension.class)
class UserControllerTest {


    private MockMvc mockMvc;

    @Mock
    private UserFacade userFacade;

    @Mock
    private UserCommandService userCommandService;

    @Mock
    private RefreshTokenService refreshTokenService;

    private static final long REFRESH_EXPIRATION = 1209600000L;


    @BeforeEach
    void setUp() {
        // 쿠키 속성까지 응답에서 확인하려고 mock이 아닌 실제 팩토리를 씀
        UserController userController = new UserController(userFacade, userCommandService, refreshTokenService,
                new RefreshTokenCookieFactory(REFRESH_EXPIRATION));

        mockMvc = MockMvcBuilders.standaloneSetup(userController)
                .setCustomArgumentResolvers(new AuthArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    // 테스트끼리 인증 정보가 섞이지 않게 비움
    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 로그인하면_200과_액세스_토큰을_본문으로_반환한다() throws Exception {
        // given
        given(userCommandService.login(new LoginRequest("jieun@example.com", "DemoPass!2026"), "127.0.0.1"))
                .willReturn(LoginResult.of("accessToken", "refreshToken"));

        // when & then
        mockMvc.perform(post("/api/users/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "jieun@example.com", "password": "DemoPass!2026"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.accessToken").value("accessToken"));
    }

    @Test
    void 리프레시_토큰은_본문이_아닌_쿠키로_내려간다() throws Exception {
        // when
        var result = performLogin();

        // then
        result.andExpect(jsonPath("$.data.refreshToken").doesNotExist());
        assertThat(refreshTokenCookieAttributes(result)).contains("refreshToken=refreshToken");
    }

    // 쿠키 속성은 옵션이 추가돼도 서로 영향이 없도록 속성마다 테스트를 나눔
    @Test
    void 리프레시_토큰_쿠키는_HttpOnly다() throws Exception {
        assertThat(refreshTokenCookieAttributes(performLogin())).contains("HttpOnly");
    }

    @Test
    void 리프레시_토큰_쿠키는_Secure다() throws Exception {
        assertThat(refreshTokenCookieAttributes(performLogin())).contains("Secure");
    }

    @Test
    void 리프레시_토큰_쿠키의_Path는_reissue다() throws Exception {
        assertThat(refreshTokenCookieAttributes(performLogin())).contains("Path=/api/auth/reissue");
    }

    @Test
    void 리프레시_토큰_쿠키는_리프레시_토큰_만료_시간만큼_유지된다() throws Exception {
        assertThat(refreshTokenCookieAttributes(performLogin())).contains("Max-Age=1209600");
    }

    private static final String RESET_TICKET = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXw";

    @Test
    void 비밀번호를_재설정하면_200을_반환하고_이메일을_소문자로_바꿔_서비스를_호출한다() throws Exception {
        // when & then
        performResetPassword("Jieun@Example.com", RESET_TICKET, "NewPass1!", "NewPass1!")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        then(userCommandService).should().validateResetPassword(
                new ResetPasswordRequest("jieun@example.com", RESET_TICKET, "NewPass1!", "NewPass1!"));
    }

    @ParameterizedTest
    @CsvSource({
            "PASSWORD_CONFIRMATION_MISMATCH, 400, MEMBER_004",
            "EMAIL_VERIFICATION_REQUIRED, 400, MAIL_006",
            "MEMBER_NOT_FOUND, 404, MEMBER_001",
            "SOCIAL_ACCOUNT_USER, 400, MEMBER_010"
    })
    void 비밀번호_재설정에_실패하면_오류_코드에_맞는_상태와_응답이_내려간다(ErrorCode errorCode, int status, String code) throws Exception {
        // given
        willThrow(new BusinessException(errorCode)).given(userCommandService).validateResetPassword(any());

        // when & then
        performResetPassword("jieun@example.com", RESET_TICKET, "NewPass1!", "NewPass1!")
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(code));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            """
            {"emailVerificationTicket": "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXw", "password": "NewPass1!", "passwordConfirm": "NewPass1!"}
            """,
            """
            {"email": "jieun@example.com", "password": "NewPass1!", "passwordConfirm": "NewPass1!"}
            """,
            """
            {"email": "jieun@example.com", "emailVerificationTicket": "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXw", "passwordConfirm": "NewPass1!"}
            """,
            """
            {"email": "jieun@example.com", "emailVerificationTicket": "short", "password": "NewPass1!", "passwordConfirm": "NewPass1!"}
            """,
            """
            {"email": "jieun@example.com", "emailVerificationTicket": "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXw", "password": "weak", "passwordConfirm": "weak"}
            """
    })
    void 비밀번호_재설정_요청이_올바르지_않으면_400이고_서비스를_호출하지_않는다(String body) throws Exception {
        // when & then
        mockMvc.perform(post("/api/users/password-reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        then(userCommandService).should(never()).validateResetPassword(any());
    }

    private ResultActions performResetPassword(String email, String ticket, String password, String passwordConfirm)
            throws Exception {
        return mockMvc.perform(post("/api/users/password-reset")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "%s", "emailVerificationTicket": "%s", "password": "%s", "passwordConfirm": "%s"}
                        """.formatted(email, ticket, password, passwordConfirm)));
    }

    private ResultActions performLogin() throws Exception {
        given(userCommandService.login(any(LoginRequest.class), anyString()))
                .willReturn(LoginResult.of("accessToken", "refreshToken"));

        return mockMvc.perform(post("/api/users/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "jieun@example.com", "password": "DemoPass!2026"}
                        """));
    }

    // 문자열 일부가 우연히 겹쳐 통과하지 않도록 ; 단위로 쪼개서 속성 하나씩 비교함
    private List<String> refreshTokenCookieAttributes(ResultActions result) {
        String setCookie = result.andReturn().getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).isNotNull();
        return Arrays.stream(setCookie.split(";")).map(String::trim).toList();
    }

    @Test
    void 시도_횟수를_초과하면_429와_Retry_After_헤더를_반환하고_쿠키를_내리지_않는다() throws Exception {
        // given
        given(userCommandService.login(any(LoginRequest.class), anyString()))
                .willThrow(new RateLimitExceededException(ErrorCode.TOO_MANY_LOGIN_ATTEMPTS, 540));

        // when & then
        mockMvc.perform(post("/api/users/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "jieun@example.com", "password": "Wrong1!"}
                                """))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "540"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
                .andExpect(jsonPath("$.code").value(ErrorCode.TOO_MANY_LOGIN_ATTEMPTS.getCode()));
    }

    @Test
    void 요청의_원격_주소를_로그인에_전달한다() throws Exception {
        // given
        given(userCommandService.login(any(LoginRequest.class), eq("203.0.113.7")))
                .willReturn(LoginResult.of("accessToken", "refreshToken"));

        // when & then
        mockMvc.perform(post("/api/users/login")
                        .with(request -> {
                            request.setRemoteAddr("203.0.113.7");
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "jieun@example.com", "password": "DemoPass!2026"}
                                """))
                .andExpect(status().isOk());
        then(userCommandService).should().login(any(LoginRequest.class), eq("203.0.113.7"));
    }

    @Test
    void 이메일_형식이_올바르지_않으면_400을_반환하고_로그인을_시도하지_않는다() throws Exception {
        // when & then
        mockMvc.perform(post("/api/users/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "not-an-email", "password": "DemoPass!2026"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_INPUT.getCode()));
        then(userCommandService).should(never()).login(any(), any());
    }

    @Test
    void 비밀번호가_비어있으면_400을_반환하고_로그인을_시도하지_않는다() throws Exception {
        // when & then
        mockMvc.perform(post("/api/users/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "jieun@example.com", "password": ""}
                                """))
                .andExpect(status().isBadRequest());
        then(userCommandService).should(never()).login(any(), any());
    }

    @Test
    void 자격_증명이_틀리면_401을_반환하고_쿠키를_내리지_않는다() throws Exception {
        // given
        given(userCommandService.login(any(LoginRequest.class), anyString()))
                .willThrow(new BusinessException(ErrorCode.INVALID_CREDENTIALS));

        // when
        var result = mockMvc.perform(post("/api/users/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "jieun@example.com", "password": "Wrong1!"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_CREDENTIALS.getCode()))
                .andReturn();

        // then
        assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE)).isNull();
    }

    @Test
    void 로그아웃하면_204를_반환하고_본문은_없다() throws Exception {
        // given
        authenticate(1L);

        // when & then
        mockMvc.perform(post("/api/auth/logout"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        then(refreshTokenService).should().deleteRefreshToken(1L);
    }

    // 로그인 때와 속성이 다르면 브라우저가 다른 쿠키로 취급해서 안 지워지므로 같은 속성으로 만료시키는지 확인함
    @Test
    void 로그아웃하면_리프레시_토큰_쿠키를_만료시킨다() throws Exception {
        // given
        authenticate(1L);

        // when
        var result = mockMvc.perform(post("/api/auth/logout"));

        // then
        assertThat(refreshTokenCookieAttributes(result))
                .contains("refreshToken=", "Max-Age=0", "HttpOnly", "Secure", "Path=/api/auth/reissue");
    }

    @Test
    void 인증_정보가_없으면_로그아웃은_401을_반환하고_삭제를_시도하지_않는다() throws Exception {
        // when & then
        mockMvc.perform(post("/api/auth/logout"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHORIZED.getCode()));
        then(refreshTokenService).should(never()).deleteRefreshToken(any());
    }

    @Test
    void 토큰_삭제에_실패하면_503을_반환하고_쿠키는_지우지_않는다() throws Exception {
        // given
        authenticate(1L);
        willThrow(new BusinessException(ErrorCode.TOKEN_REVOKE_FAILED)).given(refreshTokenService).deleteRefreshToken(1L);

        // when
        var result = mockMvc.perform(post("/api/auth/logout"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(ErrorCode.TOKEN_REVOKE_FAILED.getCode()))
                .andReturn();

        // then
        // 서버에 토큰이 남아있는데 쿠키만 지우면 사용자가 다시 시도할 수 없어서 쿠키를 그대로 둠
        assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE)).isNull();
    }

    private void authenticate(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(AuthUser.of(userId, UserRole.BASIC), null, AuthorityUtils.NO_AUTHORITIES));
    }

    @Test
    void 재발급하면_200과_새_액세스_토큰을_본문으로_반환한다() throws Exception {
        // given
        given(userCommandService.reissue("oldRefreshToken")).willReturn(LoginResult.of("newAccessToken", "newRefreshToken"));

        // when & then
        mockMvc.perform(post("/api/auth/reissue").cookie(new Cookie("refreshToken", "oldRefreshToken")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").value("newAccessToken"))
                .andExpect(jsonPath("$.data.refreshToken").doesNotExist());
    }

    @Test
    void 재발급하면_새_리프레시_토큰을_HttpOnly_Secure_쿠키로_내려준다() throws Exception {
        // given
        given(userCommandService.reissue("oldRefreshToken")).willReturn(LoginResult.of("newAccessToken", "newRefreshToken"));

        // when
        var result = mockMvc.perform(post("/api/auth/reissue").cookie(new Cookie("refreshToken", "oldRefreshToken")));

        // then
        assertThat(refreshTokenCookieAttributes(result))
                .contains("refreshToken=newRefreshToken", "HttpOnly", "Secure", "Path=/api/auth/reissue", "Max-Age=1209600");
    }

    @Test
    void 쿠키가_없어도_Facade에_null을_넘겨_401_처리를_맡긴다() throws Exception {
        // given
        given(userCommandService.reissue(null)).willThrow(new BusinessException(ErrorCode.INVALID_TOKEN));

        // when & then
        mockMvc.perform(post("/api/auth/reissue"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_TOKEN.getCode()));
    }

    @Test
    void 인증에_실패하면_401을_반환하고_리프레시_토큰_쿠키를_만료시킨다() throws Exception {
        // given
        given(userCommandService.reissue("oldRefreshToken")).willThrow(new BusinessException(ErrorCode.TOKEN_REUSED));

        // when
        var result = mockMvc.perform(post("/api/auth/reissue").cookie(new Cookie("refreshToken", "oldRefreshToken")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.TOKEN_REUSED.getCode()));

        // then
        assertThat(refreshTokenCookieAttributes(result))
                .contains("refreshToken=", "Max-Age=0", "HttpOnly", "Secure", "Path=/api/auth/reissue");
    }

    @Test
    void 다른_기기_로그인이_감지되면_전용_코드로_401을_반환한다() throws Exception {
        // given
        given(userCommandService.reissue("oldRefreshToken")).willThrow(new BusinessException(ErrorCode.LOGGED_IN_ELSEWHERE));

        // when & then
        mockMvc.perform(post("/api/auth/reissue").cookie(new Cookie("refreshToken", "oldRefreshToken")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.LOGGED_IN_ELSEWHERE.getCode()));
    }

    @Test
    void 방금_교체된_토큰이면_409를_반환하고_쿠키는_지우지_않는다() throws Exception {
        // given
        given(userCommandService.reissue("oldRefreshToken")).willThrow(new BusinessException(ErrorCode.TOKEN_ALREADY_ROTATED));

        // when
        var result = mockMvc.perform(post("/api/auth/reissue").cookie(new Cookie("refreshToken", "oldRefreshToken")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ErrorCode.TOKEN_ALREADY_ROTATED.getCode()))
                // 이 응답으로 새 토큰을 내주면 탈취된 토큰으로도 새 토큰을 받게 됨
                .andExpect(jsonPath("$.data").doesNotExist())
                .andReturn();

        // then
        // 먼저 처리된 요청이 새 쿠키를 내려줬을 수 있어서 이 응답이 쿠키를 건드리면 안 됨
        assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE)).isNull();
    }

    @Test
    void Redis_장애로_재발급에_실패하면_503을_반환하고_쿠키는_지우지_않는다() throws Exception {
        // given
        given(userCommandService.reissue("oldRefreshToken")).willThrow(new RedisConnectionFailureException("연결 실패"));

        // when
        var result = mockMvc.perform(post("/api/auth/reissue").cookie(new Cookie("refreshToken", "oldRefreshToken")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(ErrorCode.SERVICE_UNAVAILABLE.getCode()))
                .andReturn();

        // then
        // 장애가 풀린 뒤 같은 쿠키로 다시 시도할 수 있어야 함
        assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE)).isNull();
    }
}
