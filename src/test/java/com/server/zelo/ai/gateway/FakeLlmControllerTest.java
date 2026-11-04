package com.server.zelo.ai.gateway;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.server.zelo.common.auth.AuthArgumentResolver;
import com.server.zelo.common.auth.AuthUser;
import com.server.zelo.common.exception.GlobalExceptionHandler;
import com.server.zelo.common.monitoring.BusinessMetrics;
import com.server.zelo.domain.user.entity.UserRole;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

// 스프링 컨텍스트 없이 컨트롤러·@Auth 리졸버·요청 검증·예외 처리만 묶어서 요청과 응답 형식을 검증한다
@ExtendWith(MockitoExtension.class)
class FakeLlmControllerTest {

    @Mock
    private FakeLlmGateway fakeLlmGateway;

    @InjectMocks
    private FakeLlmController fakeLlmController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(fakeLlmController)
                .setCustomArgumentResolvers(new AuthArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler(new BusinessMetrics(new SimpleMeterRegistry())))
                .build();
    }

    // 테스트끼리 인증 정보가 섞이지 않게 비움
    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 로그인_사용자가_보내면_fake_LLM의_모드와_지연을_바꾼다() throws Exception {
        // given
        authenticate(1L);

        // when & then
        mockMvc.perform(post("/api/fake/llm/mode")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"DELAY\",\"delayMillis\":3500,\"delayMaxMillis\":6000}"))
                .andExpect(status().isOk());
        then(fakeLlmGateway).should().changeMode(FakeLlmGateway.Mode.DELAY);
        then(fakeLlmGateway).should().changeDelay(Duration.ofMillis(3500));
        then(fakeLlmGateway).should().changeDelayMax(Duration.ofMillis(6000));
    }

    @Test
    void 지연이_음수면_400을_반환하고_모드를_바꾸지_않는다() throws Exception {
        // given
        authenticate(1L);

        // when & then
        mockMvc.perform(post("/api/fake/llm/mode")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"DELAY\",\"delayMillis\":-1,\"delayMaxMillis\":6000}"))
                .andExpect(status().isBadRequest());
        then(fakeLlmGateway).should(never()).changeMode(any());
    }

    @Test
    void 로그인하지_않으면_401을_반환하고_모드를_바꾸지_않는다() throws Exception {
        // when & then
        mockMvc.perform(post("/api/fake/llm/mode")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"NORMAL\",\"delayMillis\":0,\"delayMaxMillis\":0}"))
                .andExpect(status().isUnauthorized());
        then(fakeLlmGateway).should(never()).changeMode(any());
    }

    private void authenticate(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(AuthUser.of(userId, UserRole.BASIC), null, AuthorityUtils.NO_AUTHORITIES));
    }
}
