package com.server.zelo.domain.chat.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

// 둘 다 생략할 수 있는 조회 조건이라 @NotNull 대신 기본값을 쓴다
public record SearchChatMessageCondition(
        // 이 메시지 ID보다 오래된 메시지를 가져온다. 없으면 가장 최근부터
        @Min(1)
        Long before,
        @Min(1)
        @Max(100)
        Integer size
) {

    private static final int DEFAULT_SIZE = 30;

    public int sizeOrDefault() {
        return size == null ? DEFAULT_SIZE : size;
    }
}
