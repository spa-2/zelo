package com.server.zelo.domain.chat.controller;

import com.server.zelo.domain.user.entity.UserRole;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.server.zelo.common.auth.AuthArgumentResolver;
import com.server.zelo.common.auth.AuthUser;
import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.common.exception.GlobalExceptionHandler;
import com.server.zelo.domain.chat.dto.request.ChooseSubstituteRequest;
import com.server.zelo.domain.chat.dto.request.SearchChatMessageCondition;
import com.server.zelo.domain.chat.dto.request.UpdateQuoteItemRequest;
import com.server.zelo.domain.chat.dto.response.ChatMessageResponse;
import com.server.zelo.domain.chat.dto.response.GetChatMessagesResponse;
import com.server.zelo.domain.chat.dto.response.QuoteCardStatus;
import com.server.zelo.domain.chat.dto.response.StartChatResponse;
import com.server.zelo.domain.chat.entity.ChatSender;
import com.server.zelo.domain.chat.service.ChatMessageCommandService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
    private ChatMessageCommandService chatMessageCommandService;

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
    void Idempotency_Key_헤더를_메시지_전송과_새_대화에_넘긴다() throws Exception {
        // given
        authenticate(1L);
        given(chatMessageCommandService.sendMessage(10L, 1L, "안녕", "7f3c2a9e-1b4d-4c8a-9e6f-2d5b8a1c3e70"))
                .willReturn(new ChatMessageResponse(101L, ChatSender.ASSISTANT, "안녕하세요.", null, null, false, LocalDateTime.now()));
        given(chatMessageCommandService.startChat(1L, "안녕", "7f3c2a9e-1b4d-4c8a-9e6f-2d5b8a1c3e70"))
                .willReturn(StartChatResponse.of(77L,
                        new ChatMessageResponse(102L, ChatSender.ASSISTANT, "안녕하세요.", null, null, false, LocalDateTime.now())));

        // when & then
        mockMvc.perform(post("/api/chat/rooms/10/messages")
                        .header("Idempotency-Key", "7f3c2a9e-1b4d-4c8a-9e6f-2d5b8a1c3e70")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"안녕\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.messageId").value(101));
        mockMvc.perform(post("/api/chat/messages")
                        .header("Idempotency-Key", "7f3c2a9e-1b4d-4c8a-9e6f-2d5b8a1c3e70")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"안녕\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.roomId").value(77));
    }

    @Test
    void 같은_키가_처리_중이면_409와_COMMON_003을_반환한다() throws Exception {
        // given
        authenticate(1L);
        given(chatMessageCommandService.sendMessage(10L, 1L, "안녕", "7f3c2a9e-1b4d-4c8a-9e6f-2d5b8a1c3e70"))
                .willThrow(new BusinessException(ErrorCode.TIMEOUT_EXCEPTION));

        // when & then
        mockMvc.perform(post("/api/chat/rooms/10/messages")
                        .header("Idempotency-Key", "7f3c2a9e-1b4d-4c8a-9e6f-2d5b8a1c3e70")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"안녕\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COMMON_003"));
    }

    @Test
    void 메시지를_보내면_201과_AI_메시지를_반환한다() throws Exception {
        // given
        authenticate(1L);
        given(chatMessageCommandService.sendMessage(10L, 1L, "김치볶음밥 재료", null))
                .willReturn(new ChatMessageResponse(101L, ChatSender.ASSISTANT, "김치와 밥이 필요해요.", null, null, false, LocalDateTime.now()));

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
        given(chatMessageCommandService.sendMessage(10L, 1L, "견적", null))
                .willReturn(new ChatMessageResponse(101L, ChatSender.ASSISTANT, "견적이에요.",
                        "{\"type\":\"QUOTE_CARD\",\"totalAmount\":28900}", QuoteCardStatus.ACTIVE, false, LocalDateTime.now()));

        // when & then
        mockMvc.perform(post("/api/chat/rooms/10/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"견적\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.payload.type").value("QUOTE_CARD"))
                .andExpect(jsonPath("$.data.payload.totalAmount").value(28900))
                .andExpect(jsonPath("$.data.quoteCardStatus").value("ACTIVE"));
    }

    @Test
    void 카드_버튼_수정은_201과_AI_메시지를_반환한다() throws Exception {
        // given
        authenticate(1L);
        given(chatMessageCommandService.updateQuoteItem(10L, 1L, new UpdateQuoteItemRequest("quote-1", 2L, 0)))
                .willReturn(new ChatMessageResponse(105L, ChatSender.ASSISTANT, "계란 (10구) 상품을 뺐어요.",
                        "{\"type\":\"QUOTE_CARD\",\"quoteId\":\"quote-2\"}", QuoteCardStatus.ACTIVE, false, LocalDateTime.now()));

        // when & then
        mockMvc.perform(post("/api/chat/rooms/10/quote-items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quoteId\":\"quote-1\",\"productId\":2,\"quantity\":0}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.content").value("계란 (10구) 상품을 뺐어요."))
                .andExpect(jsonPath("$.data.payload.quoteId").value("quote-2"))
                .andExpect(jsonPath("$.data.quoteCardStatus").value("ACTIVE"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "{\"quoteId\":\"quote-1\",\"productId\":2,\"quantity\":11}",
            "{\"quoteId\":\"quote-1\",\"productId\":2,\"quantity\":-1}",
            "{\"quoteId\":\"quote-1\",\"productId\":2}",
            "{\"quoteId\":\" \",\"productId\":2,\"quantity\":1}",
            "{\"quoteId\":\"quote-1\",\"productId\":0,\"quantity\":1}"})
    void 카드_버튼_수정_요청이_범위를_벗어나면_400과_INVALID_INPUT을_반환하고_고치지_않는다(String body) throws Exception {
        // given
        authenticate(1L);

        // when & then
        mockMvc.perform(post("/api/chat/rooms/10/quote-items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
        then(chatMessageCommandService).should(never()).updateQuoteItem(anyLong(), anyLong(), any());
    }

    @Test
    void 후보_버튼은_201과_AI_메시지를_반환한다() throws Exception {
        // given
        authenticate(1L);
        given(chatMessageCommandService.chooseSubstitute(10L, 1L, new ChooseSubstituteRequest("quote-1", "대파", true)))
                .willReturn(new ChatMessageResponse(106L, ChatSender.ASSISTANT, "수입 대파 (1단) 상품을 담았어요.",
                        "{\"type\":\"QUOTE_CARD\",\"quoteId\":\"quote-2\"}", QuoteCardStatus.ACTIVE, false, LocalDateTime.now()));

        // when & then
        mockMvc.perform(post("/api/chat/rooms/10/quote-substitutes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quoteId\":\"quote-1\",\"ingredientName\":\"대파\",\"accept\":true}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.content").value("수입 대파 (1단) 상품을 담았어요."))
                .andExpect(jsonPath("$.data.quoteCardStatus").value("ACTIVE"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "{\"quoteId\":\"quote-1\",\"ingredientName\":\"대파\"}",
            "{\"quoteId\":\" \",\"ingredientName\":\"대파\",\"accept\":true}",
            "{\"quoteId\":\"quote-1\",\"ingredientName\":\" \",\"accept\":false}"})
    void 후보_버튼_요청이_비어_있으면_400과_INVALID_INPUT을_반환하고_고르지_않는다(String body) throws Exception {
        // given
        authenticate(1L);

        // when & then
        mockMvc.perform(post("/api/chat/rooms/10/quote-substitutes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
        then(chatMessageCommandService).should(never()).chooseSubstitute(anyLong(), anyLong(), any());
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
        then(chatMessageCommandService).should(never()).sendMessage(anyLong(), anyLong(), any(), any());
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
        then(chatMessageCommandService).should(never()).sendMessage(anyLong(), anyLong(), any(), any());
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
        then(chatMessageCommandService).should(never()).sendMessage(anyLong(), anyLong(), any(), any());
    }

    @Test
    void 메시지_목록을_요청하면_200과_메시지_목록과_다음_커서를_반환한다() throws Exception {
        // given
        authenticate(1L);
        LocalDateTime now = LocalDateTime.now();
        given(chatMessageCommandService.getMessages(10L, 1L, new SearchChatMessageCondition(50L, 2)))
                .willReturn(GetChatMessagesResponse.of(List.of(
                        new ChatMessageResponse(42L, ChatSender.ASSISTANT, "답변", null, null, false, now),
                        new ChatMessageResponse(41L, ChatSender.USER, "질문", null, null, false, now)), 41L, true));

        // when & then
        mockMvc.perform(get("/api/chat/rooms/10/messages").param("before", "50").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.messages.length()").value(2))
                .andExpect(jsonPath("$.data.messages[0].messageId").value(42))
                .andExpect(jsonPath("$.data.messages[0].sender").value("ASSISTANT"))
                .andExpect(jsonPath("$.data.messages[1].sender").value("USER"))
                .andExpect(jsonPath("$.data.nextCursor").value(41))
                .andExpect(jsonPath("$.data.hasNext").value(true));
    }

    @Test
    void 조회_조건을_생략하면_빈_조건으로_조회한다() throws Exception {
        // given
        authenticate(1L);
        given(chatMessageCommandService.getMessages(10L, 1L, new SearchChatMessageCondition(null, null)))
                .willReturn(GetChatMessagesResponse.of(List.of(), null, false));

        // when & then
        mockMvc.perform(get("/api/chat/rooms/10/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.messages").isEmpty())
                .andExpect(jsonPath("$.data.nextCursor").doesNotExist())
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @ParameterizedTest
    @CsvSource({"size, 0", "size, 101", "before, 0", "before, -1"})
    void 조회_조건이_범위를_벗어나면_400과_INVALID_INPUT을_반환한다(String name, String value) throws Exception {
        // given
        authenticate(1L);

        // when & then
        mockMvc.perform(get("/api/chat/rooms/10/messages").param(name, value))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
        then(chatMessageCommandService).should(never()).getMessages(anyLong(), anyLong(), any());
    }

    @Test
    void 조회_조건이_숫자가_아니면_400과_INVALID_INPUT을_반환한다() throws Exception {
        // given
        authenticate(1L);

        // when & then
        mockMvc.perform(get("/api/chat/rooms/10/messages").param("before", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
        then(chatMessageCommandService).should(never()).getMessages(anyLong(), anyLong(), any());
    }

    @Test
    void 인증_정보_없이_목록을_요청하면_401과_AUTH_001을_반환한다() throws Exception {
        // when & then
        mockMvc.perform(get("/api/chat/rooms/10/messages"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_001"));
        then(chatMessageCommandService).should(never()).getMessages(anyLong(), anyLong(), any());
    }

    @Test
    void 새_대화를_시작하면_201과_새_방_ID와_AI_메시지를_반환한다() throws Exception {
        // given
        authenticate(1L);
        given(chatMessageCommandService.startChat(1L, "불고기 재료", null))
                .willReturn(StartChatResponse.of(77L,
                        new ChatMessageResponse(102L, ChatSender.ASSISTANT, "소고기와 간장이 필요해요.", null, null, false, LocalDateTime.now())));

        // when & then
        mockMvc.perform(post("/api/chat/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"불고기 재료\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.roomId").value(77))
                .andExpect(jsonPath("$.data.message.messageId").value(102))
                .andExpect(jsonPath("$.data.message.sender").value("ASSISTANT"))
                .andExpect(jsonPath("$.data.message.content").value("소고기와 간장이 필요해요."));
    }

    @Test
    void 새_대화의_첫_메시지가_비어_있으면_400이고_시작하지_않는다() throws Exception {
        // given
        authenticate(1L);

        // when & then
        mockMvc.perform(post("/api/chat/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
        then(chatMessageCommandService).should(never()).startChat(anyLong(), any(), any());
    }

    @Test
    void 새_대화의_첫_메시지가_1000자를_넘으면_400이다() throws Exception {
        // given
        authenticate(1L);

        // when & then
        mockMvc.perform(post("/api/chat/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"" + "가".repeat(1001) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
        then(chatMessageCommandService).should(never()).startChat(anyLong(), any(), any());
    }

    @Test
    void 인증_정보_없이_새_대화를_시작하면_401이다() throws Exception {
        // when & then
        mockMvc.perform(post("/api/chat/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"안녕\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_001"));
        then(chatMessageCommandService).should(never()).startChat(anyLong(), any(), any());
    }

    @Test
    void LLM이_실패하면_그_실패의_5xx_상태와_코드를_주고_data에_고정_문구_메시지를_담는다() throws Exception {
        // given
        authenticate(1L);
        ChatMessageResponse fallback = new ChatMessageResponse(
                102L, ChatSender.ASSISTANT, "지금은 답변을 드리기 어려워요.", "{\"type\":\"FALLBACK\",\"code\":\"AI_002\"}", null, true, LocalDateTime.now());
        given(chatMessageCommandService.sendMessage(10L, 1L, "안녕", null))
                .willThrow(new BusinessException(ErrorCode.AI_TIMEOUT, ErrorCode.AI_TIMEOUT.getMessage(), fallback));

        // when & then
        mockMvc.perform(post("/api/chat/rooms/{roomId}/messages", 10L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"안녕\"}"))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("AI_002"))
                .andExpect(jsonPath("$.message").value(ErrorCode.AI_TIMEOUT.getMessage()))
                .andExpect(jsonPath("$.data.messageId").value(102))
                .andExpect(jsonPath("$.data.payload.type").value("FALLBACK"))
                .andExpect(jsonPath("$.data.retryable").value(true));
    }

    @Test
    void 다시_시도는_201과_새_AI_메시지를_반환한다() throws Exception {
        // given
        authenticate(1L);
        given(chatMessageCommandService.retryMessage(10L, 1L, 42L))
                .willReturn(new ChatMessageResponse(43L, ChatSender.ASSISTANT, "김치와 밥이 필요해요.", null, null, false,
                        LocalDateTime.now()));

        // when & then
        mockMvc.perform(post("/api/chat/rooms/{roomId}/messages/{messageId}/retry", 10L, 42L))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.messageId").value(43))
                .andExpect(jsonPath("$.data.retryable").value(false));
    }

    @Test
    void 다시_시도할_수_없는_메시지면_409와_CHAT_004를_준다() throws Exception {
        // given
        authenticate(1L);
        given(chatMessageCommandService.retryMessage(10L, 1L, 42L))
                .willThrow(new BusinessException(ErrorCode.CHAT_RETRY_NOT_ALLOWED));

        // when & then
        mockMvc.perform(post("/api/chat/rooms/{roomId}/messages/{messageId}/retry", 10L, 42L))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHAT_004"));
    }

    @Test
    void 새_대화에서_LLM이_실패해도_오류_응답의_data에_새_방_ID와_고정_문구_메시지를_담는다() throws Exception {
        // given
        authenticate(1L);
        ChatMessageResponse fallback = new ChatMessageResponse(
                103L, ChatSender.ASSISTANT, "지금은 답변을 드리기 어려워요.", "{\"type\":\"FALLBACK\",\"code\":\"AI_008\"}", null, true, LocalDateTime.now());
        given(chatMessageCommandService.startChat(1L, "안녕", null))
                .willThrow(new BusinessException(ErrorCode.AI_CIRCUIT_OPEN, ErrorCode.AI_CIRCUIT_OPEN.getMessage(),
                        StartChatResponse.of(77L, fallback)));

        // when & then
        mockMvc.perform(post("/api/chat/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"안녕\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AI_008"))
                .andExpect(jsonPath("$.data.roomId").value(77))
                .andExpect(jsonPath("$.data.message.payload.type").value("FALLBACK"));
    }

    @Test
    void data가_없는_비즈니스_예외는_기존처럼_data가_null이다() throws Exception {
        // given
        authenticate(1L);
        given(chatMessageCommandService.sendMessage(10L, 1L, "안녕", null))
                .willThrow(new BusinessException(ErrorCode.CHAT_ROOM_ACCESS_DENIED));

        // when & then
        mockMvc.perform(post("/api/chat/rooms/{roomId}/messages", 10L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"안녕\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CHAT_002"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    private void authenticate(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(AuthUser.of(userId, UserRole.BASIC), null, AuthorityUtils.NO_AUTHORITIES));
    }
}
