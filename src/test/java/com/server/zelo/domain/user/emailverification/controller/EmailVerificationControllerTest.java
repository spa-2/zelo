package com.server.zelo.domain.user.emailverification.controller;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.common.exception.GlobalExceptionHandler;
import com.server.zelo.domain.user.emailverification.dto.ValidateEmailResponse;
import com.server.zelo.domain.user.emailverification.entity.EmailVerificationPurpose;
import com.server.zelo.domain.user.emailverification.service.EmailVerificationCommandService;
import com.server.zelo.domain.user.emailverification.service.EmailVerificationSendCommandService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 스프링 컨텍스트 없이 컨트롤러, 검증, 예외 처리만 묶어서 요청과 응답 형식을 검증한다
@ExtendWith(MockitoExtension.class)
class EmailVerificationControllerTest {

    @Mock
    private EmailVerificationCommandService emailVerificationCommandService;

    @Mock
    private EmailVerificationSendCommandService emailVerificationSendCommandService;

    @InjectMocks
    private EmailVerificationController emailVerificationController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(emailVerificationController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void 정상_요청이면_200을_반환하고_요청자_IP와_함께_서비스를_호출한다() throws Exception {
        // when & then
        perform("""
                {"email": "jieun@example.com", "purpose": "SIGNUP"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        // MockMvc의 기본 요청자 주소는 127.0.0.1
        then(emailVerificationSendCommandService).should()
                .sendEmail("jieun@example.com", EmailVerificationPurpose.SIGNUP, "127.0.0.1");
    }

    @Test
    void 비밀번호_찾기_목적도_전송_서비스에_그대로_전달한다() throws Exception {
        // when & then
        perform("""
                {"email": "jieun@example.com", "purpose": "PASSWORD_RESET"}
                """)
                .andExpect(status().isOk());

        then(emailVerificationSendCommandService).should()
                .sendEmail("jieun@example.com", EmailVerificationPurpose.PASSWORD_RESET, "127.0.0.1");
    }

    @ParameterizedTest
    @CsvSource({
            "MEMBER_NOT_FOUND, 404, MEMBER_001",
            "SOCIAL_ACCOUNT_USER, 400, MEMBER_010"
    })
    void 비밀번호_찾기_사전_검사에_실패하면_오류_코드에_맞는_상태와_응답이_내려간다(ErrorCode errorCode, int status, String code) throws Exception {
        // given
        willThrow(new BusinessException(errorCode))
                .given(emailVerificationSendCommandService).sendEmail(anyString(), any(), anyString());

        // when & then
        perform("""
                {"email": "jieun@example.com", "purpose": "PASSWORD_RESET"}
                """)
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(code));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            """
            {"purpose": "SIGNUP"}
            """,
            """
            {"email": "", "purpose": "SIGNUP"}
            """,
            """
            {"email": "not-an-email", "purpose": "SIGNUP"}
            """,
            """
            {"email": "jieun@example.com"}
            """,
            """
            {"email": "jieun@example.com", "purpose": "UNKNOWN"}
            """
    })
    void 이메일이나_목적이_올바르지_않으면_400이고_서비스를_호출하지_않는다(String body) throws Exception {
        // when & then
        perform(body).andExpect(status().isBadRequest());

        then(emailVerificationSendCommandService).should(never()).sendEmail(anyString(), any(), anyString());
    }

    @Test
    void 이메일이_50자를_넘으면_400이다() throws Exception {
        // given
        String longEmail = "a".repeat(45) + "@example.com";

        // when & then
        perform("{\"email\": \"" + longEmail + "\", \"purpose\": \"SIGNUP\"}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void 코드가_맞으면_200과_이메일_인증_티켓을_반환한다() throws Exception {
        // given
        given(emailVerificationCommandService.validateEmail("jieun@example.com", EmailVerificationPurpose.SIGNUP, "123456"))
                .willReturn(new ValidateEmailResponse("ticket"));

        // when & then
        confirm("""
                {"email": "jieun@example.com", "purpose": "SIGNUP", "code": "123456"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.emailVerificationTicket").value("ticket"));
    }

    @Test
    void 이메일은_소문자로_바꿔서_서비스에_전달한다() throws Exception {
        // given
        given(emailVerificationCommandService.validateEmail(anyString(), any(), anyString()))
                .willReturn(new ValidateEmailResponse("ticket"));

        // when
        confirm("""
                {"email": "Jieun@Example.COM", "purpose": "SIGNUP", "code": "123456"}
                """).andExpect(status().isOk());

        // then 발송 때와 같은 키로 찾으려면 대소문자가 같아야 함
        then(emailVerificationCommandService).should()
                .validateEmail("jieun@example.com", EmailVerificationPurpose.SIGNUP, "123456");
    }

    @ParameterizedTest
    @CsvSource({
            "EXPIRED_EMAIL_CODE, 400, MAIL_003",
            "INVALID_EMAIL_CODE, 400, MAIL_002",
            "TOO_MANY_EMAIL_CODE_FAILURES, 429, MAIL_004"
    })
    void 코드_확인에_실패하면_오류_코드에_맞는_상태와_응답이_내려간다(ErrorCode errorCode, int status, String code) throws Exception {
        // given
        given(emailVerificationCommandService.validateEmail(anyString(), any(), anyString()))
                .willThrow(new BusinessException(errorCode));

        // when & then
        confirm("""
                {"email": "jieun@example.com", "purpose": "SIGNUP", "code": "123456"}
                """)
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(code));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            """
            {"email": "jieun@example.com", "purpose": "SIGNUP"}
            """,
            """
            {"email": "jieun@example.com", "purpose": "SIGNUP", "code": ""}
            """,
            """
            {"email": "not-an-email", "purpose": "SIGNUP", "code": "123456"}
            """,
            """
            {"email": "jieun@example.com", "code": "123456"}
            """
    })
    void 코드_확인_요청이_올바르지_않으면_400이고_서비스를_호출하지_않는다(String body) throws Exception {
        // when & then
        confirm(body).andExpect(status().isBadRequest());

        then(emailVerificationCommandService).should(never()).validateEmail(anyString(), any(), anyString());
    }

    private ResultActions confirm(String body) throws Exception {
        return mockMvc.perform(post("/api/auth/email-verifications/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions perform(String body) throws Exception {
        return mockMvc.perform(post("/api/auth/email-verifications")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }
}
