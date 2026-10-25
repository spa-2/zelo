package com.server.zelo.domain.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.chat.dto.request.SearchChatMessageCondition;
import com.server.zelo.domain.chat.dto.response.ChatMessageResponse;
import com.server.zelo.domain.chat.dto.response.GetChatMessagesResponse;
import com.server.zelo.domain.chat.dto.response.PaymentCardPayload;
import com.server.zelo.domain.chat.dto.response.QuoteCardPayload;
import com.server.zelo.domain.chat.entity.ChatMessage;
import com.server.zelo.domain.chat.entity.ChatRoom;
import com.server.zelo.domain.chat.entity.ChatSender;
import com.server.zelo.domain.chat.repository.ChatMessageRepository;
import com.server.zelo.domain.payment.entity.PaymentStatus;
import com.server.zelo.domain.quote.model.Quote;
import com.server.zelo.domain.quote.model.QuoteItem;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.AdditionalAnswers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Limit;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class ChatMessageServiceTest {

    // 고정 문구 메시지를 payload JSON으로 가려내는 것을 실제 파싱으로 확인하려고 진짜 객체를 쓴다
    @Spy
    private JsonMapper jsonMapper = JsonMapper.builder().build();

    @Mock
    private ChatRoomService chatRoomService;

    @Mock
    private ChatMessageRepository chatMessageRepository;

    @InjectMocks
    private ChatMessageService chatMessageService;

    @Test
    void 사용자_메시지를_저장하고_방_제목과_마지막_대화_시각을_갱신한다() {
        // given
        ChatRoom chatRoom = ChatRoom.of(1L);
        LocalDateTime before = chatRoom.getLastMessageAt();
        given(chatRoomService.getOwnedChatRoom(10L, 1L)).willReturn(chatRoom);
        given(chatMessageRepository.save(any(ChatMessage.class))).willAnswer(AdditionalAnswers.returnsFirstArg());

        // when
        ChatMessage result = chatMessageService.createUserMessage(10L, 1L, "김치볶음밥 재료");

        // then
        assertThat(result.getSender()).isEqualTo(ChatSender.USER);
        assertThat(result.getRoomId()).isEqualTo(10L);
        assertThat(result.getContent()).isEqualTo("김치볶음밥 재료");
        assertThat(chatRoom.getTitle()).isEqualTo("김치볶음밥 재료");
        assertThat(chatRoom.getLastMessageAt()).isAfterOrEqualTo(before);
    }

    @Test
    void 다른_사용자의_방이면_사용자_메시지를_저장하지_않는다() {
        // given
        given(chatRoomService.getOwnedChatRoom(10L, 2L))
                .willThrow(new BusinessException(ErrorCode.CHAT_ROOM_ACCESS_DENIED));

        // when & then
        assertThatThrownBy(() -> chatMessageService.createUserMessage(10L, 2L, "안녕"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.CHAT_ROOM_ACCESS_DENIED));
        then(chatMessageRepository).should(never()).save(any());
    }

    @Test
    void AI_메시지를_저장하고_마지막_대화_시각만_갱신한다() {
        // given
        ChatRoom chatRoom = ChatRoom.of(1L);
        chatRoom.updateTitleIfAbsent("첫 메시지");
        given(chatRoomService.getOwnedChatRoom(10L, 1L)).willReturn(chatRoom);
        given(chatMessageRepository.save(any(ChatMessage.class))).willAnswer(AdditionalAnswers.returnsFirstArg());

        // when
        ChatMessage result = chatMessageService.createAssistantMessage(10L, 1L, "재료는 김치와 밥이에요.");

        // then
        assertThat(result.getSender()).isEqualTo(ChatSender.ASSISTANT);
        assertThat(result.getContent()).isEqualTo("재료는 김치와 밥이에요.");
        assertThat(chatRoom.getTitle()).isEqualTo("첫 메시지");
    }

    @Test
    void 고정_문구_메시지는_AI_메시지로_저장하고_payload에_답변_실패와_그_실패_코드를_표시한다() {
        // given
        ChatRoom chatRoom = ChatRoom.of(1L);
        chatRoom.updateTitleIfAbsent("첫 메시지");
        LocalDateTime before = chatRoom.getLastMessageAt();
        given(chatRoomService.getOwnedChatRoom(10L, 1L)).willReturn(chatRoom);
        given(chatMessageRepository.save(any(ChatMessage.class))).willAnswer(AdditionalAnswers.returnsFirstArg());

        // when
        ChatMessage result = chatMessageService.createFallbackMessage(10L, 1L, ErrorCode.AI_TIMEOUT);

        // then
        assertThat(result.getSender()).isEqualTo(ChatSender.ASSISTANT);
        assertThat(result.getRoomId()).isEqualTo(10L);
        assertThat(result.getContent()).isNotBlank();
        assertThat(result.getPayload()).isEqualTo("{\"type\":\"FALLBACK\",\"code\":\"AI_002\"}");
        assertThat(chatRoom.getTitle()).isEqualTo("첫 메시지");
        assertThat(chatRoom.getLastMessageAt()).isAfterOrEqualTo(before);
    }

    @Test
    void 고정_문구_저장_직전에_방_접근_권한이_없으면_저장하지_않는다() {
        // given
        given(chatRoomService.getOwnedChatRoom(10L, 1L))
                .willThrow(new BusinessException(ErrorCode.CHAT_ROOM_ACCESS_DENIED));

        // when & then
        assertThatThrownBy(() -> chatMessageService.createFallbackMessage(10L, 1L, ErrorCode.AI_TIMEOUT))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.CHAT_ROOM_ACCESS_DENIED));
        then(chatMessageRepository).should(never()).save(any());
    }

    @Test
    void AI_메시지_저장_직전에_방_접근_권한이_없으면_저장하지_않는다() {
        // given
        given(chatRoomService.getOwnedChatRoom(10L, 1L))
                .willThrow(new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));

        // when & then
        assertThatThrownBy(() -> chatMessageService.createAssistantMessage(10L, 1L, "답변"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.CHAT_ROOM_NOT_FOUND));
        then(chatMessageRepository).should(never()).save(any());
    }

    @Test
    void 요청한_개수보다_메시지가_많으면_개수만큼_돌려주고_마지막_ID를_다음_커서로_준다() {
        // given: size 2를 요청하면 다음 페이지 확인용으로 3개를 조회한다
        given(chatMessageRepository.findByRoomIdOrderByIdDesc(10L, Limit.of(3)))
                .willReturn(messagesWithIds(30L, 20L, 10L));

        // when
        GetChatMessagesResponse response =
                chatMessageService.getMessages(10L, 1L, new SearchChatMessageCondition(null, 2));

        // then
        assertThat(response.messages()).extracting(ChatMessageResponse::messageId).containsExactly(30L, 20L);
        assertThat(response.hasNext()).isTrue();
        assertThat(response.nextCursor()).isEqualTo(20L);
    }

    @Test
    void 요청한_개수_이하면_다음_페이지가_없고_커서는_null이다() {
        // given
        given(chatMessageRepository.findByRoomIdOrderByIdDesc(10L, Limit.of(3)))
                .willReturn(messagesWithIds(30L, 20L));

        // when
        GetChatMessagesResponse response =
                chatMessageService.getMessages(10L, 1L, new SearchChatMessageCondition(null, 2));

        // then
        assertThat(response.messages()).extracting(ChatMessageResponse::messageId).containsExactly(30L, 20L);
        assertThat(response.hasNext()).isFalse();
        assertThat(response.nextCursor()).isNull();
    }

    @Test
    void 커서를_주면_그보다_오래된_메시지를_조회한다() {
        // given
        given(chatMessageRepository.findByRoomIdAndIdLessThanOrderByIdDesc(10L, 20L, Limit.of(3)))
                .willReturn(messagesWithIds(10L));

        // when
        GetChatMessagesResponse response =
                chatMessageService.getMessages(10L, 1L, new SearchChatMessageCondition(20L, 2));

        // then
        assertThat(response.messages()).extracting(ChatMessageResponse::messageId).containsExactly(10L);
        then(chatMessageRepository).should(never()).findByRoomIdOrderByIdDesc(any(), any());
    }

    @Test
    void 개수를_정하지_않으면_30개를_기준으로_조회한다() {
        // given
        given(chatMessageRepository.findByRoomIdOrderByIdDesc(10L, Limit.of(31))).willReturn(List.of());

        // when
        GetChatMessagesResponse response =
                chatMessageService.getMessages(10L, 1L, new SearchChatMessageCondition(null, null));

        // then
        assertThat(response.messages()).isEmpty();
        assertThat(response.hasNext()).isFalse();
    }

    @Test
    void 다른_사용자의_방이면_메시지를_조회하지_않는다() {
        // given
        given(chatRoomService.getOwnedChatRoom(10L, 2L))
                .willThrow(new BusinessException(ErrorCode.CHAT_ROOM_ACCESS_DENIED));

        // when & then
        assertThatThrownBy(() -> chatMessageService.getMessages(10L, 2L, new SearchChatMessageCondition(null, null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.CHAT_ROOM_ACCESS_DENIED));
        then(chatMessageRepository).should(never()).findByRoomIdOrderByIdDesc(any(), any());
    }

    @Test
    void 새_대화는_방을_만들고_그_방에_첫_사용자_메시지를_저장하며_제목을_정한다() {
        // given
        ChatRoom chatRoom = ChatRoom.of(1L);
        ReflectionTestUtils.setField(chatRoom, "id", 77L);
        given(chatRoomService.createAndGetChatRoom(1L)).willReturn(chatRoom);
        given(chatMessageRepository.save(any(ChatMessage.class))).willAnswer(AdditionalAnswers.returnsFirstArg());

        // when
        ChatMessage result = chatMessageService.createRoomWithFirstMessage(1L, "불고기 재료");

        // then
        assertThat(result.getRoomId()).isEqualTo(77L);
        assertThat(result.getSender()).isEqualTo(ChatSender.USER);
        assertThat(result.getContent()).isEqualTo("불고기 재료");
        assertThat(chatRoom.getTitle()).isEqualTo("불고기 재료");
    }

    @Test
    void 새_대화의_첫_메시지가_비어_있으면_예외가_발생하고_메시지를_저장하지_않는다() {
        // given
        ChatRoom chatRoom = ChatRoom.of(1L);
        ReflectionTestUtils.setField(chatRoom, "id", 77L);
        given(chatRoomService.createAndGetChatRoom(1L)).willReturn(chatRoom);

        // when & then: 실제로는 이 예외로 트랜잭션이 롤백되어 방도 남지 않는다 (통합 테스트에서 확인)
        assertThatThrownBy(() -> chatMessageService.createRoomWithFirstMessage(1L, "  "))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
        then(chatMessageRepository).should(never()).save(any());
    }

    @Test
    void 견적_카드_메시지는_카드를_JSON_payload로_저장한다() {
        // given
        ChatRoom chatRoom = ChatRoom.of(1L);
        given(chatRoomService.getOwnedChatRoom(10L, 1L)).willReturn(chatRoom);
        given(chatMessageRepository.save(any(ChatMessage.class))).willAnswer(AdditionalAnswers.returnsFirstArg());
        LocalDateTime now = LocalDateTime.of(2026, 10, 23, 12, 0);
        QuoteCardPayload card = QuoteCardPayload.from(new Quote("quote-1", 1L, 10L, "김치볶음밥",
                List.of(new QuoteItem(1L, "SID-0008", "백김치", "1kg", 12_000L, 1, 12_000L)), List.of(),
                12_000L, now, now.plusMinutes(10)));

        // when
        ChatMessage result = chatMessageService.createQuoteCardMessage(10L, 1L, "견적을 준비했어요.", card);

        // then
        assertThat(result.getSender()).isEqualTo(ChatSender.ASSISTANT);
        assertThat(result.getContent()).isEqualTo("견적을 준비했어요.");
        assertThat(result.getPayload()).contains("\"type\":\"QUOTE_CARD\"").contains("\"quoteId\":\"quote-1\"");
    }

    @Test
    void 대화_기록은_이번_메시지_앞의_최근_10개를_오래된_순으로_돌려준다() {
        // given: 저장소는 최신 메시지부터 준다
        ChatMessage answer = ChatMessage.of(10L, ChatSender.ASSISTANT, "김치, 밥, 계란이 필요해요.");
        ChatMessage question = ChatMessage.of(10L, ChatSender.USER, "김치볶음밥 재료 알려 줘");
        given(chatMessageRepository.findByRoomIdAndIdLessThanOrderByIdDesc(10L, 5L, Limit.of(10)))
                .willReturn(List.of(answer, question));

        // when
        List<ChatMessage> history = chatMessageService.findHistory(10L, 5L);

        // then
        assertThat(history).containsExactly(question, answer);
    }

    @Test
    void 대화_기록에서_고정_문구_메시지는_뺀다() {
        // given: DB(jsonb)는 키 순서와 공백을 바꿔 돌려준다
        ChatMessage fallback = ChatMessage.of(10L, ChatSender.ASSISTANT, "지금은 답변을 드리기 어려워요. 잠시 후 다시 말씀해 주세요.",
                "{\"code\": \"AI_002\", \"type\": \"FALLBACK\"}");
        ChatMessage question = ChatMessage.of(10L, ChatSender.USER, "김치볶음밥 재료 알려 줘");
        given(chatMessageRepository.findByRoomIdAndIdLessThanOrderByIdDesc(10L, 5L, Limit.of(10)))
                .willReturn(List.of(fallback, question));

        // when
        List<ChatMessage> history = chatMessageService.findHistory(10L, 5L);

        // then
        assertThat(history).containsExactly(question);
    }

    @Test
    void 고정_문구가_아닌_payload가_있는_메시지는_대화_기록에_남긴다() {
        // given: 견적 카드처럼 다른 종류의 payload
        ChatMessage card = ChatMessage.of(10L, ChatSender.ASSISTANT, "견적을 준비했어요.", "{\"type\": \"QUOTE_CARD\"}");
        given(chatMessageRepository.findByRoomIdAndIdLessThanOrderByIdDesc(10L, 5L, Limit.of(10)))
                .willReturn(List.of(card));

        // when
        List<ChatMessage> history = chatMessageService.findHistory(10L, 5L);

        // then
        assertThat(history).containsExactly(card);
    }

    @Test
    void 대화_기록이_2000자를_넘으면_오래된_메시지부터_뺀다() {
        // given: 최신부터 900자, 900자, 900자 → 앞의 두 개(1800자)까지만 들어간다
        ChatMessage newest = ChatMessage.of(10L, ChatSender.USER, "가".repeat(900));
        ChatMessage middle = ChatMessage.of(10L, ChatSender.ASSISTANT, "나".repeat(900));
        ChatMessage oldest = ChatMessage.of(10L, ChatSender.USER, "다".repeat(900));
        given(chatMessageRepository.findByRoomIdAndIdLessThanOrderByIdDesc(10L, 5L, Limit.of(10)))
                .willReturn(List.of(newest, middle, oldest));

        // when
        List<ChatMessage> history = chatMessageService.findHistory(10L, 5L);

        // then
        assertThat(history).containsExactly(middle, newest);
    }

    @Test
    void 결제_카드를_시스템_알림으로_저장하고_마지막_대화_시각을_갱신한다() {
        // given
        ChatRoom chatRoom = ChatRoom.of(1L);
        LocalDateTime before = chatRoom.getLastMessageAt();
        given(chatRoomService.getOwnedChatRoom(10L, 1L)).willReturn(chatRoom);
        given(chatMessageRepository.save(any(ChatMessage.class))).willAnswer(AdditionalAnswers.returnsFirstArg());
        PaymentCardPayload card = paymentCard(PaymentStatus.PAID, null);

        // when
        ChatMessage result = chatMessageService.createPaymentCardMessage(10L, 1L, card);

        // then
        assertThat(result.getSender()).isEqualTo(ChatSender.SYSTEM_EVENT);
        assertThat(result.getContent()).isEqualTo("결제가 완료됐어요.");
        assertThat(result.getPayload()).contains("\"type\":\"PAYMENT_CARD\"").contains("\"orderId\":3");
        assertThat(chatRoom.getLastMessageAt()).isAfterOrEqualTo(before);
    }

    // 빈 칸은 사유 없음(null)
    @ParameterizedTest
    @CsvSource({
            "PAID, , 결제가 완료됐어요.",
            "FAILED, , 결제에 실패했어요.",
            "CANCELED, USER_CANCELED, 결제를 취소했어요.",
            "CANCELED, AMOUNT_MISMATCH, 결제 금액이 주문 금액과 달라 결제를 취소하고 환불했어요.",
            "CANCELED, ORDER_CLOSED, 이미 처리가 끝난 주문이라 결제된 금액을 환불했어요.",
            "CANCELED, , 결제를 취소했어요."
    })
    void 결제_결과와_취소_사유에_맞는_문장으로_결제_카드를_저장한다(PaymentStatus status, String reason, String content) {
        // given
        given(chatRoomService.getOwnedChatRoom(10L, 1L)).willReturn(ChatRoom.of(1L));
        given(chatMessageRepository.save(any(ChatMessage.class))).willAnswer(AdditionalAnswers.returnsFirstArg());

        // when
        ChatMessage result = chatMessageService.createPaymentCardMessage(10L, 1L, paymentCard(status, reason));

        // then
        assertThat(result.getContent()).isEqualTo(content);
        assertThat(result.getPayload()).contains("\"status\":\"" + status + "\"");
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"READY", "CONFIRM_PENDING"})
    void 결제_결과가_정해지지_않았으면_결제_카드를_저장하지_않는다(PaymentStatus status) {
        // given
        PaymentCardPayload card = paymentCard(status, null);

        // when & then
        assertThatThrownBy(() -> chatMessageService.createPaymentCardMessage(10L, 1L, card))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
        then(chatMessageRepository).should(never()).save(any());
    }

    private PaymentCardPayload paymentCard(PaymentStatus status, String reason) {
        return new PaymentCardPayload(PaymentCardPayload.TYPE, 3L, "김치볶음밥 재료",
                List.of(new PaymentCardPayload.Item("김치", "1kg", 1, 12_000L)),
                12_000L, status, reason, LocalDateTime.of(2026, 10, 24, 12, 0));
    }

    private List<ChatMessage> messagesWithIds(Long... ids) {
        List<ChatMessage> messages = new ArrayList<>();
        for (Long id : ids) {
            ChatMessage chatMessage = ChatMessage.of(10L, ChatSender.USER, "메시지 " + id);
            ReflectionTestUtils.setField(chatMessage, "id", id);
            messages.add(chatMessage);
        }
        return messages;
    }
}
