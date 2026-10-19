package com.server.zelo.domain.chat.dto.response;

import java.util.List;

// 메시지는 최신순이다. 다음 페이지가 있으면 nextCursor를 before로 넘겨 더 오래된 메시지를 가져온다
public record GetChatMessagesResponse(
        List<ChatMessageResponse> messages,
        Long nextCursor,
        boolean hasNext
) {

    public static GetChatMessagesResponse of(List<ChatMessageResponse> messages, Long nextCursor, boolean hasNext) {
        return new GetChatMessagesResponse(messages, nextCursor, hasNext);
    }
}
