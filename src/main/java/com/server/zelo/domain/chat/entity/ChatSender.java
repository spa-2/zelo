package com.server.zelo.domain.chat.entity;

public enum ChatSender {
    USER,
    ASSISTANT,
    SYSTEM_EVENT // 결제 완료처럼 다른 도메인이 채팅방에 남기는 알림
}
