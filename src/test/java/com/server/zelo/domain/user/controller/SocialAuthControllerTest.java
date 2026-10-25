package com.server.zelo.domain.user.controller;

import com.server.zelo.common.auth.RefreshTokenCookieFactory;
import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.common.exception.GlobalExceptionHandler;
import com.server.zelo.domain.user.dto.request.CreateSocialUserRequest;
import com.server.zelo.domain.user.dto.response.LoginResult;
import com.server.zelo.domain.user.service.SocialFacade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.server.zelo.domain.user.dto.request.SocialLoginRequest;
import com.server.zelo.domain.user.dto.response.ExistingMemberResult;
import com.server.zelo.domain.user.dto.response.NewMemberResult;
import com.server.zelo.domain.user.service.SocialCommandService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

// 스프링 컨텍스트 없이 컨트롤러, 검증, 예외 처리만 묶어서 요청과 응답 형식을 검증한다
@ExtendWith(MockitoExtension.class)
class SocialAuthControllerTest {

    private static final long REFRESH_EXPIRATION = 1209600000L;
    private static final String VALID_BODY = """
            {"signupToken": "ticket", "phoneNumber": "010-1234-5678",
             "place": "집", "addresses": "서울 강남구", "zipCode": "12345"}
            """;

    @Mock
    private SocialFacade socialFacade;

    @Mock
    private SocialCommandService socialCommandService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // 쿠키 속성까지 응답에서 확인하려고 mock이 아닌 실제 팩토리를 씀
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new SocialAuthController(socialFacade, socialCommandService,
                                new RefreshTokenCookieFactory(REFRESH_EXPIRATION)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void 소셜_가입하면_201과_액세스_토큰을_본문으로_반환한다() throws Exception {
        // given
        given(socialFacade.createSocialUserWithAddress(any(CreateSocialUserRequest.class)))
                .willReturn(LoginResult.of("accessToken", "refreshToken"));

        // when & then
        performSignup(VALID_BODY)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.accessToken").value("accessToken"))
                // 리프레시 토큰은 본문이 아닌 쿠키로만 내려감
                .andExpect(jsonPath("$.data.refreshToken").doesNotExist());
    }

    @Test
    void 소셜_가입하면_리프레시_토큰을_HttpOnly_Secure_쿠키로_내려준다() throws Exception {
        // given
        given(socialFacade.createSocialUserWithAddress(any(CreateSocialUserRequest.class)))
                .willReturn(LoginResult.of("accessToken", "refreshToken"));

        // when
        ResultActions result = performSignup(VALID_BODY);

        // then
        assertThat(cookieAttributes(result))
                .contains("refreshToken=refreshToken", "HttpOnly", "Secure", "Path=/api/auth/reissue", "Max-Age=1209600");
    }

    @Test
    void 하이픈_없는_전화번호는_정규화해서_Facade에_전달한다() throws Exception {
        // given
        given(socialFacade.createSocialUserWithAddress(any(CreateSocialUserRequest.class)))
                .willReturn(LoginResult.of("accessToken", "refreshToken"));

        // when
        performSignup(VALID_BODY.replace("010-1234-5678", "01012345678"));

        // then
        ArgumentCaptor<CreateSocialUserRequest> captor = ArgumentCaptor.forClass(CreateSocialUserRequest.class);
        then(socialFacade).should().createSocialUserWithAddress(captor.capture());
        assertThat(captor.getValue().signupToken()).isEqualTo("ticket");
        assertThat(captor.getValue().phoneNumber()).isEqualTo("010-1234-5678");
    }

    @Test
    void 번호표가_비어있으면_400을_반환하고_가입하지_않는다() throws Exception {
        // when & then
        performSignup(VALID_BODY.replace("\"ticket\"", "\"\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_INPUT.getCode()));
        then(socialFacade).should(never()).createSocialUserWithAddress(any());
    }

    @Test
    void 전화번호_형식이_올바르지_않으면_400을_반환하고_번호표를_소비하지_않는다() throws Exception {
        // when & then
        // 검증은 컨트롤러에서 끝나므로, 입력이 틀린 요청 때문에 번호표가 지워지지 않음
        performSignup(VALID_BODY.replace("010-1234-5678", "not-a-phone"))
                .andExpect(status().isBadRequest());
        then(socialFacade).should(never()).createSocialUserWithAddress(any());
    }

    @Test
    void 요청_본문이_없으면_400을_반환한다() throws Exception {
        // when & then
        mockMvc.perform(post("/api/auth/social/signup").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
        then(socialFacade).should(never()).createSocialUserWithAddress(any());
    }

    @Test
    void 번호표가_만료되었으면_401과_INVALID_SIGNUP_TOKEN을_반환하고_쿠키를_내리지_않는다() throws Exception {
        // given
        given(socialFacade.createSocialUserWithAddress(any(CreateSocialUserRequest.class)))
                .willThrow(new BusinessException(ErrorCode.INVALID_SIGNUP_TOKEN));

        // when
        ResultActions result = performSignup(VALID_BODY)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_SIGNUP_TOKEN.getCode()));

        // then
        assertThat(result.andReturn().getResponse().getHeader(HttpHeaders.SET_COOKIE)).isNull();
    }

    @Test
    void 이미_가입된_이메일이면_409와_DUPLICATE_EMAIL을_반환하고_쿠키를_내리지_않는다() throws Exception {
        // given
        given(socialFacade.createSocialUserWithAddress(any(CreateSocialUserRequest.class)))
                .willThrow(new BusinessException(ErrorCode.DUPLICATE_EMAIL));

        // when
        ResultActions result = performSignup(VALID_BODY)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ErrorCode.DUPLICATE_EMAIL.getCode()));

        // then
        assertThat(result.andReturn().getResponse().getHeader(HttpHeaders.SET_COOKIE)).isNull();
    }

    @Test
    void 락_대기에_실패하면_409와_TIMEOUT_EXCEPTION을_반환한다() throws Exception {
        // given
        given(socialFacade.createSocialUserWithAddress(any(CreateSocialUserRequest.class)))
                .willThrow(new BusinessException(ErrorCode.TIMEOUT_EXCEPTION));

        // when & then
        performSignup(VALID_BODY)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ErrorCode.TIMEOUT_EXCEPTION.getCode()));
    }

    private ResultActions performSignup(String body) throws Exception {
        return mockMvc.perform(post("/api/auth/social/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    // 문자열 일부가 우연히 겹쳐 통과하지 않도록 ; 단위로 쪼개서 속성 하나씩 비교함
    private List<String> cookieAttributes(ResultActions result) {
        String setCookie = result.andReturn().getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).isNotNull();
        return Arrays.stream(setCookie.split(";")).map(String::trim).toList();
    }

    // ===== 소셜 로그인 =====

    private static final String LOGIN_BODY = """
            {"code": "authorization-code", "redirectUri": "http://localhost:3000/callback"}
            """;

    @Test
    void 이미_가입한_사용자가_로그인하면_200과_액세스_토큰을_반환한다() throws Exception {
        // given
        given(socialCommandService.login(eq("google"), any(SocialLoginRequest.class)))
                .willReturn(new ExistingMemberResult("accessToken", "refreshToken"));

        // when & then
        performLogin("google", LOGIN_BODY)
                // 로그인은 새로 만드는 것이 아니라서 201이 아님
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.signupRequired").value(false))
                .andExpect(jsonPath("$.data.accessToken").value("accessToken"))
                // 값이 없는 필드는 응답에서 빠짐
                .andExpect(jsonPath("$.data.signupToken").doesNotExist())
                .andExpect(jsonPath("$.data.email").doesNotExist())
                // 리프레시 토큰은 본문이 아닌 쿠키로만 내려감
                .andExpect(jsonPath("$.data.refreshToken").doesNotExist());
    }

    @Test
    void 이미_가입한_사용자가_로그인하면_리프레시_토큰을_HttpOnly_Secure_쿠키로_내려준다() throws Exception {
        // given
        given(socialCommandService.login(eq("google"), any(SocialLoginRequest.class)))
                .willReturn(new ExistingMemberResult("accessToken", "refreshToken"));

        // when
        ResultActions result = performLogin("google", LOGIN_BODY);

        // then
        assertThat(cookieAttributes(result))
                .contains("refreshToken=refreshToken", "HttpOnly", "Secure", "Path=/api/auth/reissue", "Max-Age=1209600");
    }

    @Test
    void 처음_온_사용자가_로그인하면_200과_가입용_정보를_반환하고_쿠키는_내리지_않는다() throws Exception {
        // given
        given(socialCommandService.login(eq("google"), any(SocialLoginRequest.class)))
                .willReturn(new NewMemberResult("ticket", "jieun@gmail.com", "지은"));

        // when
        ResultActions result = performLogin("google", LOGIN_BODY)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.signupRequired").value(true))
                .andExpect(jsonPath("$.data.signupToken").value("ticket"))
                .andExpect(jsonPath("$.data.email").value("jieun@gmail.com"))
                .andExpect(jsonPath("$.data.name").value("지은"))
                .andExpect(jsonPath("$.data.accessToken").doesNotExist());

        // then
        // 가입을 끝내기 전에는 로그인된 것이 아님
        assertThat(result.andReturn().getResponse().getHeader(HttpHeaders.SET_COOKIE)).isNull();
    }

    @Test
    void 경로의_제공자와_요청_본문을_Command에_그대로_전달한다() throws Exception {
        // given
        given(socialCommandService.login(eq("google"), any(SocialLoginRequest.class)))
                .willReturn(new NewMemberResult("ticket", "jieun@gmail.com", "지은"));
        String verifier = "a".repeat(43);

        // when
        performLogin("google", """
                {"code": "authorization-code", "redirectUri": "http://localhost:3000/callback", "codeVerifier": "%s"}
                """.formatted(verifier));

        // then
        ArgumentCaptor<SocialLoginRequest> captor = ArgumentCaptor.forClass(SocialLoginRequest.class);
        then(socialCommandService).should().login(eq("google"), captor.capture());
        assertThat(captor.getValue().code()).isEqualTo("authorization-code");
        assertThat(captor.getValue().redirectUri()).isEqualTo("http://localhost:3000/callback");
        assertThat(captor.getValue().codeVerifier()).isEqualTo(verifier);
    }

    @Test
    void code가_비어있으면_400을_반환하고_로그인하지_않는다() throws Exception {
        // when & then
        performLogin("google", LOGIN_BODY.replace("authorization-code", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_INPUT.getCode()));
        then(socialCommandService).should(never()).login(any(), any());
    }

    @Test
    void redirectUri가_비어있으면_400을_반환하고_로그인하지_않는다() throws Exception {
        // when & then
        performLogin("google", LOGIN_BODY.replace("http://localhost:3000/callback", ""))
                .andExpect(status().isBadRequest());
        then(socialCommandService).should(never()).login(any(), any());
    }

    @Test
    void codeVerifier가_43자보다_짧으면_400을_반환한다() throws Exception {
        // when & then
        // 빈 문자열이나 짧은 값을 그대로 구글에 보내면 구글이 거절하므로 미리 막음
        performLogin("google", """
                {"code": "authorization-code", "redirectUri": "http://localhost:3000/callback", "codeVerifier": "short"}
                """)
                .andExpect(status().isBadRequest());
        then(socialCommandService).should(never()).login(any(), any());
    }

    @Test
    void 로그인_요청_본문이_없으면_400을_반환한다() throws Exception {
        // when & then
        mockMvc.perform(post("/api/auth/social/google/login").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
        then(socialCommandService).should(never()).login(any(), any());
    }

    @ParameterizedTest
    @EnumSource(value = ErrorCode.class, names = {"SOCIAL_AUTH_FAILED", "SOCIAL_EMAIL_REQUIRED",
            "SOCIAL_EMAIL_CONFLICT", "UNSUPPORTED_SOCIAL_PROVIDER", "SOCIAL_PROVIDER_UNAVAILABLE"})
    void 소셜_로그인_실패는_에러_코드의_상태로_응답하고_쿠키를_내리지_않는다(ErrorCode errorCode) throws Exception {
        // given
        given(socialCommandService.login(any(), any(SocialLoginRequest.class)))
                .willThrow(new BusinessException(errorCode));

        // when
        ResultActions result = performLogin("google", LOGIN_BODY)
                .andExpect(status().is(errorCode.getStatus().value()))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(errorCode.getCode()));

        // then
        assertThat(result.andReturn().getResponse().getHeader(HttpHeaders.SET_COOKIE)).isNull();
    }

    private ResultActions performLogin(String provider, String body) throws Exception {
        return mockMvc.perform(post("/api/auth/social/{provider}/login", provider)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }
}
