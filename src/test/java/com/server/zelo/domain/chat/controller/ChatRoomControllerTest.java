package com.server.zelo.domain.chat.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.server.zelo.common.auth.AuthArgumentResolver;
import com.server.zelo.common.auth.AuthUser;
import com.server.zelo.common.exception.GlobalExceptionHandler;
import com.server.zelo.domain.chat.dto.CreateChatRoomResponse;
import com.server.zelo.domain.chat.service.ChatRoomService;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

// 스프링 컨텍스트 없이 컨트롤러·@Auth 리졸버·예외 처리만 묶어서 요청과 응답 형식을 검증한다
@ExtendWith(MockitoExtension.class)
class ChatRoomControllerTest {

    @Mock
    private ChatRoomService chatRoomService;

    @InjectMocks
    private ChatRoomController chatRoomController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(chatRoomController)
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
    void 로그인한_사용자가_요청하면_201과_생성된_방을_반환한다() throws Exception {
        // given
        authenticate(1L);
        given(chatRoomService.createChatRoom(1L))
                .willReturn(new CreateChatRoomResponse(10L, null, LocalDateTime.now(), LocalDateTime.now()));

        // when & then
        mockMvc.perform(post("/api/chat/rooms"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.roomId").value(10))
                .andExpect(jsonPath("$.data.title").doesNotExist());
    }

    @Test
    void 인증_정보가_없으면_401과_AUTH_001을_반환한다() throws Exception {
        // when & then
        mockMvc.perform(post("/api/chat/rooms"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("AUTH_001"));
        then(chatRoomService).should(never()).createChatRoom(any());
    }

    private void authenticate(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(AuthUser.of(userId), null, AuthorityUtils.NO_AUTHORITIES));
    }
}
