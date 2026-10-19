package com.server.zelo.domain.chat.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.server.zelo.common.auth.AuthArgumentResolver;
import com.server.zelo.common.auth.AuthUser;
import com.server.zelo.common.exception.GlobalExceptionHandler;
import com.server.zelo.domain.chat.dto.CreateChatRoomResponse;
import com.server.zelo.domain.chat.dto.GetChatRoomResponse;
import com.server.zelo.domain.chat.service.ChatRoomService;
import java.time.LocalDateTime;
import java.util.List;
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

    @Test
    void 로그인한_사용자가_목록을_요청하면_200과_방_목록을_반환한다() throws Exception {
        // given
        authenticate(1L);
        LocalDateTime now = LocalDateTime.now();
        given(chatRoomService.getChatRooms(1L)).willReturn(List.of(
                new GetChatRoomResponse(20L, "김치볶음밥 재료 준비해 줘", now, now),
                new GetChatRoomResponse(10L, null, now.minusHours(1), now.minusHours(1))));

        // when & then
        mockMvc.perform(get("/api/chat/rooms"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].roomId").value(20))
                .andExpect(jsonPath("$.data[0].title").value("김치볶음밥 재료 준비해 줘"))
                .andExpect(jsonPath("$.data[1].roomId").value(10))
                .andExpect(jsonPath("$.data[1].title").doesNotExist());
    }

    @Test
    void 방이_없으면_빈_배열을_반환한다() throws Exception {
        // given
        authenticate(1L);
        given(chatRoomService.getChatRooms(1L)).willReturn(List.of());

        // when & then
        mockMvc.perform(get("/api/chat/rooms"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void 인증_정보_없이_목록을_요청하면_401과_AUTH_001을_반환한다() throws Exception {
        // when & then
        mockMvc.perform(get("/api/chat/rooms"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_001"));
        then(chatRoomService).should(never()).getChatRooms(any());
    }

    private void authenticate(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(AuthUser.of(userId), null, AuthorityUtils.NO_AUTHORITIES));
    }
}
