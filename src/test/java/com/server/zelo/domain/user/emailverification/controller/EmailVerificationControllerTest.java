package com.server.zelo.domain.user.emailverification.controller;

import com.server.zelo.common.exception.GlobalExceptionHandler;
import com.server.zelo.domain.user.emailverification.entity.EmailVerificationPurpose;
import com.server.zelo.domain.user.emailverification.service.EmailVerificationCommandService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
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
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 스프링 컨텍스트 없이 컨트롤러, 검증, 예외 처리만 묶어서 요청과 응답 형식을 검증한다
@ExtendWith(MockitoExtension.class)
class EmailVerificationControllerTest {

    @Mock
    private EmailVerificationCommandService emailVerificationCommandService;

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
        then(emailVerificationCommandService).should()
                .sendEmail("jieun@example.com", EmailVerificationPurpose.SIGNUP, "127.0.0.1");
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

        then(emailVerificationCommandService).should(never()).sendEmail(anyString(), any(), anyString());
    }

    @Test
    void 이메일이_50자를_넘으면_400이다() throws Exception {
        // given
        String longEmail = "a".repeat(45) + "@example.com";

        // when & then
        perform("{\"email\": \"" + longEmail + "\", \"purpose\": \"SIGNUP\"}")
                .andExpect(status().isBadRequest());
    }

    private ResultActions perform(String body) throws Exception {
        return mockMvc.perform(post("/api/auth/email-verifications")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }
}
