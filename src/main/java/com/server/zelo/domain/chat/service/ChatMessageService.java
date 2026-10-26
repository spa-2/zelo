package com.server.zelo.domain.chat.service;

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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

// 메시지 한 건 저장을 하나의 짧은 트랜잭션으로 끝낸다. LLM 호출은 이 클래스 밖(트랜잭션 밖)에서 한다
@Service
@RequiredArgsConstructor
public class ChatMessageService {

    // LLM 답을 받지 못했을 때 남기는 AI 메시지. 방을 다시 열어도 그때 받은 문구가 보이도록 저장하고,
    // 프론트와 이후 대화 처리는 payload.type(FALLBACK)으로 일반 답변과 구분한다
    private static final String FALLBACK_CONTENT = "지금은 답변을 드리기 어려워요. 잠시 후 다시 말씀해 주세요.";
    private static final String FALLBACK_TYPE = "FALLBACK";
    private static final String FALLBACK_PAYLOAD_FORMAT = "{\"type\":\"" + FALLBACK_TYPE + "\",\"code\":\"%s\"}";
    // 결제 결과 메시지. 취소는 사유마다 사용자가 알아야 할 내용(환불 여부)이 달라 문장을 나눈다.
    // 이 문장은 LLM에 system 역할로 가서 규칙처럼 읽히므로, 주문명·상품명처럼 사용자나 LLM에서 온 글자를 넣지 않는다
    // (그런 값은 화면용 payload에만 담는다)
    private static final String PAYMENT_COMPLETED_CONTENT = "결제가 완료됐어요.";
    private static final String PAYMENT_FAILED_CONTENT = "결제에 실패했어요.";
    private static final String PAYMENT_CANCELED_CONTENT = "결제를 취소했어요.";
    private static final String PAYMENT_AMOUNT_MISMATCH_CONTENT = "결제 금액이 주문 금액과 달라 결제를 취소하고 환불했어요.";
    private static final String PAYMENT_ORDER_CLOSED_CONTENT = "이미 처리가 끝난 주문이라 결제된 금액을 환불했어요.";
    // LLM에 함께 보내는 앞 대화의 상한. 사용자·AI가 다섯 번쯤 주고받은 양이고,
    // 메시지 하나는 1000자 제한이 있어도 기록이 쌓이면 프롬프트가 끝없이 길어지므로 글자 수로도 자른다
    private static final int HISTORY_SIZE = 10;
    private static final int HISTORY_MAX_LENGTH = 2_000;

    private final JsonMapper jsonMapper;
    private final ChatRoomService chatRoomService;
    private final ChatMessageRepository chatMessageRepository;

    @Transactional
    public ChatMessage createUserMessage(Long roomId, Long userId, String content) {
        ChatRoom chatRoom = chatRoomService.getOwnedChatRoom(roomId, userId);
        return saveUserMessage(chatRoom, roomId, content);
    }

    // 방 생성과 첫 메시지 저장을 한 트랜잭션으로 묶어, 메시지 저장이 실패하면 방도 남지 않게 한다(빈 방 방지)
    @Transactional
    public ChatMessage createRoomWithFirstMessage(Long userId, String content) {
        ChatRoom chatRoom = chatRoomService.createAndGetChatRoom(userId);
        return saveUserMessage(chatRoom, chatRoom.getId(), content);
    }

    @Transactional
    public ChatMessage createAssistantMessage(Long roomId, Long userId, String content) {
        return saveAssistantMessage(roomId, userId, content, null);
    }

    // 견적 카드를 payload(JSON)로 함께 저장해, 방을 다시 열어도 그때 본 카드가 그대로 보이게 한다
    @Transactional
    public ChatMessage createQuoteCardMessage(Long roomId, Long userId, String content, QuoteCardPayload quoteCard) {
        return saveAssistantMessage(roomId, userId, content, jsonMapper.writeValueAsString(quoteCard));
    }

    @Transactional
    public ChatMessage createFallbackMessage(Long roomId, Long userId, ErrorCode errorCode) {
        String payload = FALLBACK_PAYLOAD_FORMAT.formatted(errorCode.getCode());
        return saveAssistantMessage(roomId, userId, FALLBACK_CONTENT, payload);
    }

    @Transactional(readOnly = true)
    public GetChatMessagesResponse getMessages(Long roomId, Long userId, SearchChatMessageCondition condition) {
        chatRoomService.getOwnedChatRoom(roomId, userId);

        int size = condition.sizeOrDefault();
        // count 쿼리 없이 다음 페이지 여부를 알기 위해 하나 더 가져온다
        Limit limit = Limit.of(size + 1);
        List<ChatMessage> fetched = condition.before() == null
                ? chatMessageRepository.findByRoomIdOrderByIdDesc(roomId, limit)
                : chatMessageRepository.findByRoomIdAndIdLessThanOrderByIdDesc(roomId, condition.before(), limit);

        boolean hasNext = fetched.size() > size;
        List<ChatMessage> page = hasNext ? fetched.subList(0, size) : fetched;
        Long nextCursor = hasNext ? page.getLast().getId() : null;

        List<ChatMessageResponse> messages = page.stream()
                .map(ChatMessageResponse::from)
                .toList();
        return GetChatMessagesResponse.of(messages, nextCursor, hasNext);
    }

    // LLM에 함께 보낼 앞 대화를 오래된 순으로 준다. 방금 저장한 이번 메시지(beforeMessageId)는 따로 보내므로 뺀다.
    // 고정 문구는 대화 내용이 아니라서 뺀다(LLM이 "답변을 드리기 어려워요"를 따라 하지 않게).
    // 최근 10개 안에서 빼므로 고정 문구가 있으면 10개보다 적게 갈 수 있다
    @Transactional(readOnly = true)
    public List<ChatMessage> findHistory(Long roomId, Long beforeMessageId) {
        List<ChatMessage> recentMessages = chatMessageRepository.findByRoomIdAndIdLessThanOrderByIdDesc(
                roomId, beforeMessageId, Limit.of(HISTORY_SIZE));

        // 최신 메시지부터 담다가 글자 수 상한을 넘으면 멈춰, 잘릴 때는 오래된 대화부터 빠지게 한다
        List<ChatMessage> history = new ArrayList<>();
        int length = 0;
        for (ChatMessage chatMessage : recentMessages) {
            if (isFallback(chatMessage)) {
                continue;
            }
            length += chatMessage.getContent().length();
            if (length > HISTORY_MAX_LENGTH) {
                break;
            }
            history.add(chatMessage);
        }
        Collections.reverse(history);
        return history;
    }

    // 다른 도메인이 남기는 알림이라 AI 답변과 구분되게 SYSTEM_EVENT로 저장한다
    @Transactional
    public ChatMessage createPaymentCardMessage(Long roomId, Long userId, PaymentCardPayload paymentCard) {
        String content = paymentResultContent(paymentCard);
        ChatRoom chatRoom = chatRoomService.getOwnedChatRoom(roomId, userId);
        ChatMessage chatMessage = chatMessageRepository.save(ChatMessage.of(roomId, ChatSender.SYSTEM_EVENT,
                content, jsonMapper.writeValueAsString(paymentCard)));
        chatRoom.updateLastMessageAt(LocalDateTime.now());
        return chatMessage;
    }

    // 결과가 정해지지 않은 상태(READY, CONFIRM_PENDING)는 알릴 내용이 없으므로 저장하지 않는다.
    // 사유가 없거나 모르는 취소는 결제창 취소처럼 안내한다(환불했다고 잘못 말하지 않게)
    private String paymentResultContent(PaymentCardPayload paymentCard) {
        return switch (paymentCard.status()) {
            case PAID -> PAYMENT_COMPLETED_CONTENT;
            case FAILED -> PAYMENT_FAILED_CONTENT;
            case CANCELED -> switch (Objects.requireNonNullElse(paymentCard.reason(), "")) {
                case PaymentCardPayload.AMOUNT_MISMATCH -> PAYMENT_AMOUNT_MISMATCH_CONTENT;
                case PaymentCardPayload.ORDER_CLOSED -> PAYMENT_ORDER_CLOSED_CONTENT;
                default -> PAYMENT_CANCELED_CONTENT;
            };
            case READY, CONFIRM_PENDING -> throw new BusinessException(ErrorCode.INVALID_INPUT);
        };
    }

    // DB(jsonb)가 키 순서·공백을 바꿔 돌려주므로 문자열 비교가 아니라 JSON의 type 값으로 본다
    private boolean isFallback(ChatMessage chatMessage) {
        return chatMessage.getPayload() != null
                && FALLBACK_TYPE.equals(jsonMapper.readTree(chatMessage.getPayload()).path("type").asString(""));
    }

    // LLM 응답을 기다리는 사이 방 상태가 바뀌었을 수 있어 저장 직전에 소유자를 다시 확인
    private ChatMessage saveAssistantMessage(Long roomId, Long userId, String content, String payload) {
        ChatRoom chatRoom = chatRoomService.getOwnedChatRoom(roomId, userId);
        ChatMessage chatMessage = chatMessageRepository.save(ChatMessage.of(roomId, ChatSender.ASSISTANT, content, payload));
        chatRoom.updateLastMessageAt(LocalDateTime.now());
        return chatMessage;
    }

    private ChatMessage saveUserMessage(ChatRoom chatRoom, Long roomId, String content) {
        ChatMessage chatMessage = chatMessageRepository.save(ChatMessage.of(roomId, ChatSender.USER, content));
        chatRoom.updateTitleIfAbsent(content);
        chatRoom.updateLastMessageAt(LocalDateTime.now());
        return chatMessage;
    }
}
