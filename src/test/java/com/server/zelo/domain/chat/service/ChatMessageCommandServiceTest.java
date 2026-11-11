package com.server.zelo.domain.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.common.exception.RateLimitExceededException;
import com.server.zelo.common.monitoring.BusinessMetrics;
import com.server.zelo.domain.chat.dto.request.SearchChatMessageCondition;
import com.server.zelo.domain.chat.dto.response.ChatMessageResponse;
import com.server.zelo.domain.chat.dto.response.GetChatMessagesResponse;
import com.server.zelo.domain.chat.dto.response.QuoteCardStatus;
import com.server.zelo.domain.chat.dto.response.StartChatResponse;
import com.server.zelo.domain.chat.entity.ChatMessage;
import com.server.zelo.domain.chat.entity.ChatSender;
import com.server.zelo.domain.chat.model.ChatIdempotencyRecord;
import com.server.zelo.domain.order.service.OrderService;
import com.server.zelo.domain.quote.model.Quote;
import com.server.zelo.domain.quote.service.QuoteService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.test.util.ReflectionTestUtils;

// 메시지 시작·전송·재시도의 입구 순서(멱등 → 횟수 제한 → 저장 → 답변)와 메시지 목록의 카드 상태.
// 답변(ChatReplyCommandService)과 멱등 처리(ChatIdempotencyService)는 각자의 테스트에서 보고, 여기서는 무엇을 넘기는지만 본다
@ExtendWith(MockitoExtension.class)
class ChatMessageCommandServiceTest {

    private static final String KEY = "7f3c2a9e-1b4d-4c8a-9e6f-2d5b8a1c3e70";
    // [다시 시도]는 서버가 FALLBACK 메시지 ID(42)로 키를 만든다
    private static final String RETRY_KEY = UUID.nameUUIDFromBytes("retry:42".getBytes(StandardCharsets.UTF_8)).toString();
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 27, 12, 0);

    @Mock
    private QuoteService quoteService;

    @Mock
    private OrderService orderService;

    @Mock
    private ChatMessageService chatMessageService;

    @Mock
    private ChatRateLimitService chatRateLimitService;

    @Mock
    private ChatIdempotencyService chatIdempotencyService;

    @Mock
    private ChatReplyCommandService chatReplyCommandService;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @Spy
    private BusinessMetrics businessMetrics = new BusinessMetrics(meterRegistry);

    @InjectMocks
    private ChatMessageCommandService chatMessageCommandService;

    @Test
    void 메시지는_횟수_확인_사용자_메시지_저장_앞_대화_조회_답변_순서로_처리한다() {
        // given
        List<ChatMessage> history = List.of(ChatMessage.of(10L, ChatSender.USER, "초등학생 둘이랑 셋이 김치볶음밥"));
        ChatMessageResponse answer = response(43L, "백김치로 준비할게요.");
        given(chatMessageService.createUserMessage(10L, 1L, "그거 맵지 않게 해 줘"))
                .willReturn(userMessage(10L, 5L, "그거 맵지 않게 해 줘"));
        given(chatMessageService.findHistory(10L, 5L)).willReturn(history);
        given(chatReplyCommandService.<ChatMessageResponse>replyTo(eq(10L), eq(1L), eq(history), eq("그거 맵지 않게 해 줘"), any()))
                .willAnswer(invocation -> applyToResponse(invocation.getArgument(4), answer));

        // when
        ChatMessageResponse response = chatMessageCommandService.sendMessage(10L, 1L, "그거 맵지 않게 해 줘");

        // then: 이번 메시지는 user로 따로 보내므로 앞 대화는 방금 저장한 메시지 앞까지만 조회한다
        InOrder order = inOrder(chatRateLimitService, chatMessageService, chatReplyCommandService);
        order.verify(chatRateLimitService).validateLimit(1L);
        order.verify(chatMessageService).createUserMessage(10L, 1L, "그거 맵지 않게 해 줘");
        order.verify(chatMessageService).findHistory(10L, 5L);
        order.verify(chatReplyCommandService).replyTo(eq(10L), eq(1L), eq(history), eq("그거 맵지 않게 해 줘"), any());
        assertThat(response).isSameAs(answer);
    }

    @Test
    void 방_접근_권한이_없으면_답변을_만들지_않는다() {
        // given
        given(chatMessageService.createUserMessage(10L, 2L, "안녕"))
                .willThrow(new BusinessException(ErrorCode.CHAT_ROOM_ACCESS_DENIED));

        // when & then
        assertThatThrownBy(() -> chatMessageCommandService.sendMessage(10L, 2L, "안녕"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.CHAT_ROOM_ACCESS_DENIED));
        then(chatReplyCommandService).shouldHaveNoInteractions();
    }

    @Test
    void 횟수_제한에_걸리면_사용자_메시지를_저장하지_않고_답변을_만들지_않는다() {
        // given
        willThrow(rateLimited()).given(chatRateLimitService).validateLimit(1L);

        // when & then
        assertThatThrownBy(() -> chatMessageCommandService.sendMessage(10L, 1L, "안녕", null))
                .isInstanceOfSatisfying(RateLimitExceededException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.CHAT_RATE_LIMITED));
        then(chatMessageService).shouldHaveNoInteractions();
        then(chatReplyCommandService).shouldHaveNoInteractions();
    }

    @Test
    void 새_대화는_방과_첫_메시지를_저장하고_앞_대화_없이_답하며_답하는_동안만_로그에_방_ID를_붙인다() {
        // given
        ChatMessageResponse answer = response(44L, "소고기와 간장이 필요해요.");
        given(chatMessageService.createRoomWithFirstMessage(1L, "불고기 재료")).willReturn(userMessage(77L, 5L, "불고기 재료"));
        given(chatReplyCommandService.<StartChatResponse>replyTo(eq(77L), eq(1L), eq(List.of()), eq("불고기 재료"), any()))
                .willAnswer(invocation -> {
                    assertThat(MDC.get("roomId")).isEqualTo("77");
                    return applyToResponse(invocation.getArgument(4), answer);
                });

        // when
        StartChatResponse response = chatMessageCommandService.startChat(1L, "불고기 재료");

        // then
        InOrder order = inOrder(chatRateLimitService, chatMessageService, chatReplyCommandService);
        order.verify(chatRateLimitService).validateLimit(1L);
        order.verify(chatMessageService).createRoomWithFirstMessage(1L, "불고기 재료");
        order.verify(chatReplyCommandService).replyTo(eq(77L), eq(1L), eq(List.of()), eq("불고기 재료"), any());
        then(chatMessageService).should(never()).findHistory(anyLong(), anyLong());
        assertThat(response.roomId()).isEqualTo(77L);
        assertThat(response.message()).isSameAs(answer);
        assertThat(MDC.get("roomId")).isNull();
    }

    @Test
    void 새_대화의_답변이_실패해도_실패_응답의_data는_새_방_ID를_담는_모양으로_만든다() {
        // given: 답변 쪽은 LLM이 실패하면 넘겨받은 변환으로 data를 만든다
        ChatMessageResponse fallback = response(45L, "지금은 답변을 드리기 어려워요.");
        given(chatMessageService.createRoomWithFirstMessage(1L, "안녕")).willReturn(userMessage(77L, 5L, "안녕"));
        given(chatReplyCommandService.replyTo(eq(77L), eq(1L), eq(List.of()), eq("안녕"), any()))
                .willAnswer(invocation -> {
                    Object data = applyToResponse(invocation.getArgument(4), fallback);
                    throw new BusinessException(ErrorCode.AI_CIRCUIT_OPEN, ErrorCode.AI_CIRCUIT_OPEN.getMessage(), data);
                });

        // when & then
        assertThatThrownBy(() -> chatMessageCommandService.startChat(1L, "안녕"))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AI_CIRCUIT_OPEN);
                    assertThat(exception.getData()).isEqualTo(StartChatResponse.of(77L, fallback));
                });
        assertThat(MDC.get("roomId")).isNull();
    }

    @Test
    void 새_대화의_첫_메시지_저장이_실패하면_답변을_만들지_않는다() {
        // given
        given(chatMessageService.createRoomWithFirstMessage(1L, " ")).willThrow(new BusinessException(ErrorCode.INVALID_INPUT));

        // when & then
        assertThatThrownBy(() -> chatMessageCommandService.startChat(1L, " ")).isInstanceOf(BusinessException.class);
        then(chatReplyCommandService).shouldHaveNoInteractions();
    }

    @Test
    void 새_대화가_횟수_제한에_걸리면_방을_만들지_않는다() {
        // given
        willThrow(rateLimited()).given(chatRateLimitService).validateLimit(1L);

        // when & then
        assertThatThrownBy(() -> chatMessageCommandService.startChat(1L, "불고기 재료", null))
                .isInstanceOf(RateLimitExceededException.class);
        then(chatMessageService).shouldHaveNoInteractions();
        then(chatReplyCommandService).shouldHaveNoInteractions();
    }

    @Test
    void 멱등_키가_없으면_멱등_처리_없이_바로_처리한다() {
        // given
        ChatMessageResponse answer = response(43L, "답변");
        given(chatMessageService.createUserMessage(10L, 1L, "안녕")).willReturn(userMessage(10L, 5L, "안녕"));
        given(chatReplyCommandService.<ChatMessageResponse>replyTo(eq(10L), eq(1L), any(), eq("안녕"), any()))
                .willAnswer(invocation -> applyToResponse(invocation.getArgument(4), answer));

        // when
        ChatMessageResponse response = chatMessageCommandService.sendMessage(10L, 1L, "안녕", null);

        // then
        assertThat(response).isSameAs(answer);
        then(chatIdempotencyService).shouldHaveNoInteractions();
    }

    @Test
    void 멱등_키가_있으면_방과_문장으로_요청을_구분하고_사용자_메시지를_커밋한_뒤_알린다() {
        // given: 멱등 처리는 처음 온 키라고 보고 넘긴 작업을 실행한다
        ChatMessageResponse answer = response(43L, "답변");
        boolean[] committed = new boolean[1];
        given(chatMessageService.createUserMessage(10L, 1L, "안녕")).willReturn(userMessage(10L, 5L, "안녕"));
        given(chatReplyCommandService.<ChatMessageResponse>replyTo(eq(10L), eq(1L), any(), eq("안녕"), any()))
                .willAnswer(invocation -> {
                    assertThat(committed[0]).isTrue();
                    return applyToResponse(invocation.getArgument(4), answer);
                });
        given(chatIdempotencyService.<ChatMessageResponse>runOnce(eq(1L), eq(KEY), eq("room:10:안녕"), any(), any(), any(), any()))
                .willAnswer(invocation -> invocation.<Function<Runnable, ChatMessageResponse>>getArgument(3)
                        .apply(() -> committed[0] = true));

        // when
        ChatMessageResponse response = chatMessageCommandService.sendMessage(10L, 1L, "안녕", KEY);

        // then: 결과로 남길 방 ID와 메시지를 응답에서 꺼내는 방법도 넘긴다
        assertThat(response).isSameAs(answer);
        Function<ChatMessageResponse, Long> roomIdOf = runOnceArgument(4);
        Function<ChatMessageResponse, ChatMessageResponse> messageOf = runOnceArgument(5);
        assertThat(roomIdOf.apply(answer)).isEqualTo(10L);
        assertThat(messageOf.apply(answer)).isSameAs(answer);
    }

    @Test
    void 같은_키로_다시_온_요청은_첫_응답을_돌려받고_횟수를_세거나_다시_처리하지_않는다() {
        // given: 멱등 처리가 저장해 둔 첫 응답을 돌려준다
        ChatMessageResponse first = response(42L, "답변");
        given(chatIdempotencyService.<ChatMessageResponse>runOnce(eq(1L), eq(KEY), eq("room:10:안녕"), any(), any(), any(), any()))
                .willReturn(first);

        // when
        ChatMessageResponse response = chatMessageCommandService.sendMessage(10L, 1L, "안녕", KEY);

        // then
        assertThat(response).isSameAs(first);
        then(chatRateLimitService).shouldHaveNoInteractions();
        then(chatMessageService).shouldHaveNoInteractions();
        then(chatReplyCommandService).shouldHaveNoInteractions();
    }

    @Test
    void 첫_응답은_그때_저장한_AI_메시지와_카드_상태로_다시_만들고_다시_시도_여부도_그때처럼_계산한다() {
        // given
        String payload = "{\"type\":\"QUOTE_CARD\"}";
        given(chatIdempotencyService.<ChatMessageResponse>runOnce(eq(1L), eq(KEY), eq("room:10:계란 2개 더"), any(), any(), any(), any()))
                .willReturn(null);
        given(chatMessageService.getMessage(42L)).willReturn(assistantMessage(42L, "계란을 2개 더했어요.", payload));
        given(chatMessageService.isRetryable(payload)).willReturn(false);
        chatMessageCommandService.sendMessage(10L, 1L, "계란 2개 더", KEY);
        Function<ChatIdempotencyRecord, ChatMessageResponse> firstResponse = runOnceArgument(6);

        // when
        ChatMessageResponse response = firstResponse.apply(
                new ChatIdempotencyRecord(true, "hash", 10L, 42L, QuoteCardStatus.ACTIVE, null));

        // then
        assertThat(response.messageId()).isEqualTo(42L);
        assertThat(response.content()).isEqualTo("계란을 2개 더했어요.");
        assertThat(response.quoteCardStatus()).isEqualTo(QuoteCardStatus.ACTIVE);
        assertThat(response.retryable()).isFalse();
    }

    @Test
    void 새_대화는_문장으로_요청을_구분하고_첫_응답은_그때_만든_방으로_다시_만든다() {
        // given
        given(chatIdempotencyService.<StartChatResponse>runOnce(eq(1L), eq(KEY), eq("start:불고기 재료"), any(), any(), any(), any()))
                .willReturn(null);
        given(chatMessageService.getMessage(44L)).willReturn(assistantMessage(44L, "소고기와 간장이 필요해요.", null));
        chatMessageCommandService.startChat(1L, "불고기 재료", KEY);
        Function<StartChatResponse, Long> roomIdOf = runOnceArgument(4);
        Function<StartChatResponse, ChatMessageResponse> messageOf = runOnceArgument(5);
        Function<ChatIdempotencyRecord, StartChatResponse> firstResponse = runOnceArgument(6);
        StartChatResponse started = StartChatResponse.of(77L, response(44L, "소고기와 간장이 필요해요."));

        // when
        StartChatResponse replayed = firstResponse.apply(new ChatIdempotencyRecord(true, "hash", 77L, 44L, null, null));

        // then
        assertThat(roomIdOf.apply(started)).isEqualTo(77L);
        assertThat(messageOf.apply(started)).isSameAs(started.message());
        assertThat(replayed.roomId()).isEqualTo(77L);
        assertThat(replayed.message().messageId()).isEqualTo(44L);
    }

    @Test
    void 다시_시도는_FALLBACK_메시지_ID로_키를_만들고_앞_사용자_메시지로_답변만_다시_만든다() {
        // given: 41번 "안녕"의 답이 42번 고정 문구로 끝난 방
        ChatMessageResponse answer = response(43L, "답변");
        given(chatIdempotencyService.<ChatMessageResponse>runOnce(eq(1L), eq(RETRY_KEY), eq("room:10:retry:42"),
                any(), any(), any(), any()))
                .willAnswer(invocation -> invocation.<Function<Runnable, ChatMessageResponse>>getArgument(3).apply(() -> { }));
        given(chatMessageService.getRetryTarget(10L, 1L, 42L)).willReturn(userMessage(10L, 41L, "안녕"));
        given(chatMessageService.findHistory(10L, 41L)).willReturn(List.of());
        given(chatReplyCommandService.<ChatMessageResponse>replyTo(eq(10L), eq(1L), eq(List.of()), eq("안녕"), any()))
                .willAnswer(invocation -> applyToResponse(invocation.getArgument(4), answer));

        // when
        ChatMessageResponse response = chatMessageCommandService.retryMessage(10L, 1L, 42L);

        // then
        assertThat(response).isSameAs(answer);
        then(chatMessageService).should(never()).createUserMessage(any(), any(), any());
        assertThat(counter("zelo.chat.retry", "result", "SUCCESS")).isEqualTo(1);
    }

    @Test
    void 다시_시도도_횟수에_포함되고_제한에_걸리면_답변을_만들지_않는다() {
        // given
        given(chatIdempotencyService.<ChatMessageResponse>runOnce(eq(1L), eq(RETRY_KEY), any(), any(), any(), any(), any()))
                .willAnswer(invocation -> invocation.<Function<Runnable, ChatMessageResponse>>getArgument(3).apply(() -> { }));
        willThrow(rateLimited()).given(chatRateLimitService).validateLimit(1L);

        // when & then
        assertThatThrownBy(() -> chatMessageCommandService.retryMessage(10L, 1L, 42L))
                .isInstanceOf(RateLimitExceededException.class);
        then(chatMessageService).shouldHaveNoInteractions();
        then(chatReplyCommandService).shouldHaveNoInteractions();
        assertThat(counter("zelo.chat.retry", "result", "CHAT_005")).isEqualTo(1);
    }

    @Test
    void 다시_시도할_수_없는_메시지면_답변을_만들지_않고_거절을_센다() {
        // given: 고정 문구 뒤에 새 메시지가 왔다
        given(chatIdempotencyService.<ChatMessageResponse>runOnce(eq(1L), eq(RETRY_KEY), any(), any(), any(), any(), any()))
                .willAnswer(invocation -> invocation.<Function<Runnable, ChatMessageResponse>>getArgument(3).apply(() -> { }));
        given(chatMessageService.getRetryTarget(10L, 1L, 42L))
                .willThrow(new BusinessException(ErrorCode.CHAT_RETRY_NOT_ALLOWED));

        // when & then
        assertThatThrownBy(() -> chatMessageCommandService.retryMessage(10L, 1L, 42L))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.CHAT_RETRY_NOT_ALLOWED));
        then(chatReplyCommandService).shouldHaveNoInteractions();
        assertThat(counter("zelo.chat.retry", "result", "CHAT_004")).isEqualTo(1);
    }

    @Test
    void 다시_시도도_실패하면_그_실패를_세고_답변_쪽이_만든_실패를_그대로_던진다() {
        // given
        BusinessException failed = new BusinessException(ErrorCode.AI_TIMEOUT, ErrorCode.AI_TIMEOUT.getMessage(),
                response(43L, "지금은 답변을 드리기 어려워요."));
        given(chatIdempotencyService.<ChatMessageResponse>runOnce(eq(1L), eq(RETRY_KEY), any(), any(), any(), any(), any()))
                .willAnswer(invocation -> invocation.<Function<Runnable, ChatMessageResponse>>getArgument(3).apply(() -> { }));
        given(chatMessageService.getRetryTarget(10L, 1L, 42L)).willReturn(userMessage(10L, 41L, "안녕"));
        given(chatMessageService.findHistory(10L, 41L)).willReturn(List.of());
        given(chatReplyCommandService.replyTo(eq(10L), eq(1L), eq(List.of()), eq("안녕"), any())).willThrow(failed);

        // when & then
        assertThatThrownBy(() -> chatMessageCommandService.retryMessage(10L, 1L, 42L)).isSameAs(failed);
        assertThat(counter("zelo.chat.retry", "result", "AI_002")).isEqualTo(1);
    }

    @Test
    void 메시지_목록의_견적_카드마다_현재_견적과_주문을_보고_상태를_붙인다() {
        // given: 견적 A로 주문했고, B는 C로 수정되기 전 카드, C가 현재 견적
        ChatMessageResponse user = messageResponse(1L, null);
        ChatMessageResponse cardA = messageResponse(2L, "card-A");
        ChatMessageResponse cardB = messageResponse(3L, "card-B");
        ChatMessageResponse cardC = messageResponse(4L, "card-C");
        SearchChatMessageCondition condition = new SearchChatMessageCondition(null, null);
        given(chatMessageService.getMessages(10L, 1L, condition))
                .willReturn(GetChatMessagesResponse.of(List.of(cardC, cardB, cardA, user), 1L, true));
        given(chatMessageService.findQuoteCardId("card-A")).willReturn(Optional.of("A"));
        given(chatMessageService.findQuoteCardId("card-B")).willReturn(Optional.of("B"));
        given(chatMessageService.findQuoteCardId("card-C")).willReturn(Optional.of("C"));
        given(quoteService.findQuote(10L)).willReturn(Optional.of(quote("C")));
        given(orderService.findOrderedQuoteIds(any())).willReturn(Set.of("A"));

        // when
        GetChatMessagesResponse response = chatMessageCommandService.getMessages(10L, 1L, condition);

        // then: 순서와 커서는 그대로, 사용자 메시지는 상태가 없다
        assertThat(response.messages()).extracting(ChatMessageResponse::messageId).containsExactly(4L, 3L, 2L, 1L);
        assertThat(response.messages()).extracting(ChatMessageResponse::quoteCardStatus)
                .containsExactly(QuoteCardStatus.ACTIVE, QuoteCardStatus.REPLACED, QuoteCardStatus.ORDERED, null);
        assertThat(response.nextCursor()).isEqualTo(1L);
        assertThat(response.hasNext()).isTrue();
    }

    @Test
    void 현재_견적이_없으면_주문하지_않은_카드는_모두_만료로_표시한다() {
        // given
        ChatMessageResponse cardA = messageResponse(2L, "card-A");
        ChatMessageResponse cardB = messageResponse(3L, "card-B");
        SearchChatMessageCondition condition = new SearchChatMessageCondition(null, null);
        given(chatMessageService.getMessages(10L, 1L, condition))
                .willReturn(GetChatMessagesResponse.of(List.of(cardB, cardA), null, false));
        given(chatMessageService.findQuoteCardId("card-A")).willReturn(Optional.of("A"));
        given(chatMessageService.findQuoteCardId("card-B")).willReturn(Optional.of("B"));
        given(quoteService.findQuote(10L)).willReturn(Optional.empty());
        given(orderService.findOrderedQuoteIds(any())).willReturn(Set.of("A"));

        // when
        GetChatMessagesResponse response = chatMessageCommandService.getMessages(10L, 1L, condition);

        // then
        assertThat(response.messages()).extracting(ChatMessageResponse::quoteCardStatus)
                .containsExactly(QuoteCardStatus.EXPIRED, QuoteCardStatus.ORDERED);
    }

    @Test
    void 메시지_목록에_견적_카드가_없으면_견적과_주문을_조회하지_않는다() {
        // given
        GetChatMessagesResponse messages = GetChatMessagesResponse.of(List.of(messageResponse(1L, null)), null, false);
        SearchChatMessageCondition condition = new SearchChatMessageCondition(null, null);
        given(chatMessageService.getMessages(10L, 1L, condition)).willReturn(messages);

        // when
        GetChatMessagesResponse response = chatMessageCommandService.getMessages(10L, 1L, condition);

        // then
        assertThat(response).isSameAs(messages);
        then(quoteService).should(never()).findQuote(any());
        then(orderService).should(never()).findOrderedQuoteIds(any());
    }

    // 답변 쪽은 넘겨받은 변환으로 AI 메시지를 응답 모양으로 바꾼다
    private static Object applyToResponse(Function<ChatMessageResponse, ?> toResponse, ChatMessageResponse message) {
        return toResponse.apply(message);
    }

    // runOnce에 넘긴 인자(0부터: userId, 키, 요청, 작업, 방 ID 꺼내기, 메시지 꺼내기, 첫 응답 만들기)를 꺼낸다
    @SuppressWarnings("unchecked")
    private <A> A runOnceArgument(int index) {
        return (A) mockingDetails(chatIdempotencyService).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("runOnce"))
                .findFirst()
                .orElseThrow()
                .getArgument(index);
    }

    private ChatMessageResponse response(Long messageId, String content) {
        return new ChatMessageResponse(messageId, ChatSender.ASSISTANT, content, null, null, false, NOW);
    }

    private ChatMessageResponse messageResponse(Long messageId, String payload) {
        return new ChatMessageResponse(messageId, ChatSender.ASSISTANT, "메시지 " + messageId, payload, null, false, NOW);
    }

    private double counter(String name, String... tags) {
        return meterRegistry.get(name).tags(tags).counter().count();
    }

    private static RateLimitExceededException rateLimited() {
        return new RateLimitExceededException(ErrorCode.CHAT_RATE_LIMITED, 15L);
    }

    private static ChatMessage assistantMessage(Long messageId, String content, String payload) {
        ChatMessage chatMessage = ChatMessage.of(10L, ChatSender.ASSISTANT, content, payload);
        ReflectionTestUtils.setField(chatMessage, "id", messageId);
        return chatMessage;
    }

    private static ChatMessage userMessage(Long roomId, Long messageId, String text) {
        ChatMessage message = ChatMessage.of(roomId, ChatSender.USER, text);
        ReflectionTestUtils.setField(message, "id", messageId);
        return message;
    }

    private static Quote quote(String quoteId) {
        return new Quote(quoteId, 1L, 10L, "김치볶음밥", List.of(), List.of(), 0L, NOW, NOW.plusMinutes(10));
    }
}
