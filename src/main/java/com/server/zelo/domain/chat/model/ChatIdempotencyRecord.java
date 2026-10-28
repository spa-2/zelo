package com.server.zelo.domain.chat.model;

import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.chat.dto.response.ChatMessageResponse;
import com.server.zelo.domain.chat.dto.response.QuoteCardStatus;

// 메시지 전송 멱등 키(Redis)에 저장하는 값. 응답 JSON 대신 응답한 AI 메시지 ID를 두고,
// 같은 키가 다시 오면 DB의 그 메시지로 첫 응답과 같은 응답을 다시 만든다
public record ChatIdempotencyRecord(
        boolean done, // false면 아직 처리 중
        String requestHash, // 같은 키로 다른 요청(방·문장)이 오면 거절하려고 둔다
        Long roomId, // 새 대화는 이때 만든 방이라 응답에 다시 담아야 한다
        Long messageId, // 응답한 AI 메시지(답변·카드 또는 FALLBACK 고정 문구)
        QuoteCardStatus quoteCardStatus, // 첫 응답에 붙었던 카드 상태
        ErrorCode failure // FALLBACK으로 끝났으면 그 오류 코드, 성공이면 null
) {

    public static ChatIdempotencyRecord processing(String requestHash) {
        return new ChatIdempotencyRecord(false, requestHash, null, null, null, null);
    }

    public static ChatIdempotencyRecord done(String requestHash, Long roomId, ChatMessageResponse message,
                                             ErrorCode failure) {
        return new ChatIdempotencyRecord(true, requestHash, roomId, message.messageId(),
                message.quoteCardStatus(), failure);
    }
}
