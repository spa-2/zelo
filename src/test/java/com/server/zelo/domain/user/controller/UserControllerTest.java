package com.server.zelo.domain.user.controller;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.common.exception.GlobalExceptionHandler;
import com.server.zelo.domain.user.dto.request.LoginRequest;
import com.server.zelo.domain.user.dto.response.LoginResult;
import com.server.zelo.domain.user.service.UserFacade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 스프링 컨텍스트 없이 컨트롤러·검증·예외 처리만 묶어서 요청과 응답 형식을 검증한다
@ExtendWith(MockitoExtension.class)
class UserControllerTest {

    private static final long REFRESH_EXPIRATION = 1209600000L;

    @Mock
    private UserFacade userFacade;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        UserController userController = new UserController(userFacade);
        // @Value 필드는 스프링 없이 주입되지 않아서 테스트에서 직접 넣음
        ReflectionTestUtils.setField(userController, "refreshTokenExpiration", REFRESH_EXPIRATION);

        mockMvc = MockMvcBuilders.standaloneSetup(userController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void 로그인하면_200과_액세스_토큰을_본문으로_반환한다() throws Exception {
        // given
        given(userFacade.login(new LoginRequest("jieun@example.com", "DemoPass!2026")))
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
    void 리프레시_토큰은_본문이_아닌_HttpOnly_쿠키로_내려간다() throws Exception {
        // given
        given(userFacade.login(any(LoginRequest.class)))
                .willReturn(LoginResult.of("accessToken", "refreshToken"));

        // when
        var result = mockMvc.perform(post("/api/users/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "jieun@example.com", "password": "DemoPass!2026"}
                                """))
                .andExpect(jsonPath("$.data.refreshToken").doesNotExist())
                .andReturn();

        // then
        // 옵션이 추가돼도 깨지지 않도록 지키려는 속성만 확인함
        assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE))
                .contains("refreshToken=refreshToken", "HttpOnly", "Max-Age=1209600");
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
        then(userFacade).should(never()).login(any());
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
        then(userFacade).should(never()).login(any());
    }

    @Test
    void 자격_증명이_틀리면_401을_반환하고_쿠키를_내리지_않는다() throws Exception {
        // given
        given(userFacade.login(any(LoginRequest.class)))
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
}
