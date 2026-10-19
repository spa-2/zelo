package com.server.zelo.domain.chat.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.server.zelo.common.auth.AuthArgumentResolver;
import com.server.zelo.common.auth.AuthUser;
import com.server.zelo.common.exception.GlobalExceptionHandler;
import com.server.zelo.domain.chat.dto.response.ChatMessageResponse;
import com.server.zelo.domain.chat.entity.ChatSender;
import com.server.zelo.domain.chat.service.ChatMessageOrchestrator;
import java.time.LocalDateTime;
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
class ChatMessageControllerTest {

    @Mock
    private ChatMessageOrchestrator chatMessageOrchestrator;

    @InjectMocks
    private ChatMessageController chatMessageController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(chatMessageController)
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
    void 메시지를_보내면_201과_AI_메시지를_반환한다() throws Exception {
        // given
        authenticate(1L);
        given(chatMessageOrchestrator.sendMessage(10L, 1L, "김치볶음밥 재료"))
                .willReturn(new ChatMessageResponse(101L, ChatSender.ASSISTANT, "김치와 밥이 필요해요.", null, LocalDateTime.now()));

        // when & then
        mockMvc.perform(post("/api/chat/rooms/10/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"김치볶음밥 재료\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.messageId").value(101))
                .andExpect(jsonPath("$.data.sender").value("ASSISTANT"))
                .andExpect(jsonPath("$.data.content").value("김치와 밥이 필요해요."))
                .andExpect(jsonPath("$.data.payload").doesNotExist())
                .andExpect(jsonPath("$.data.createdAt").exists());
    }

    @Test
    void payload가_있으면_문자열이_아닌_JSON_객체로_내보낸다() throws Exception {
        // given
        authenticate(1L);
        given(chatMessageOrchestrator.sendMessage(10L, 1L, "견적"))
                .willReturn(new ChatMessageResponse(101L, ChatSender.ASSISTANT, "견적이에요.",
                        "{\"type\":\"QUOTE_CARD\",\"totalAmount\":28900}", LocalDateTime.now()));

        // when & then
        mockMvc.perform(post("/api/chat/rooms/10/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"견적\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.payload.type").value("QUOTE_CARD"))
                .andExpect(jsonPath("$.data.payload.totalAmount").value(28900));
    }

    @Test
    void 빈_메시지면_400과_INVALID_INPUT을_반환하고_전송하지_않는다() throws Exception {
        // given
        authenticate(1L);

        // when & then
        mockMvc.perform(post("/api/chat/rooms/10/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
        then(chatMessageOrchestrator).should(never()).sendMessage(anyLong(), anyLong(), any());
    }

    @Test
    void 메시지가_1000자를_넘으면_400과_INVALID_INPUT을_반환한다() throws Exception {
        // given
        authenticate(1L);
        String tooLong = "가".repeat(1001);

        // when & then
        mockMvc.perform(post("/api/chat/rooms/10/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
        then(chatMessageOrchestrator).should(never()).sendMessage(anyLong(), anyLong(), any());
    }

    @Test
    void 요청_본문이_없으면_400과_INVALID_INPUT을_반환한다() throws Exception {
        // given
        authenticate(1L);

        // when & then
        mockMvc.perform(post("/api/chat/rooms/10/messages").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
    }

    @Test
    void 인증_정보가_없으면_401과_AUTH_001을_반환한다() throws Exception {
        // when & then
        mockMvc.perform(post("/api/chat/rooms/10/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"안녕\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_001"));
        then(chatMessageOrchestrator).should(never()).sendMessage(anyLong(), anyLong(), any());
    }

    private void authenticate(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(AuthUser.of(userId), null, AuthorityUtils.NO_AUTHORITIES));
    }
}
