package com.server.zelo.domain.chat.model;

// 견적 수정 지표(zelo.chat.quote.edit)의 source 태그. 견적 수정이 어디서 왔는지 구분한다
public enum QuoteEditSource {
    CHAT, // 말로 고침 (LLM 수정 계획)
    BUTTON, // 카드 ✕, −/+ 버튼
    SUBSTITUTE, // 대체 후보 [좋아요]·[빼기]
    BUDGET // 예산 후보 [바꾸기]
}
