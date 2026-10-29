package com.server.zelo.ai.gateway;

// LLM 호출의 종류. 로그와 지표(operation 태그)에서 어떤 호출이 느리거나 실패했는지 나눠 보려고 쓴다
public enum LlmOperation {
    MENU_PLAN, // 메시지의 의도·메뉴 계획
    QUOTE_EDIT_PLAN, // 견적 수정 계획
    QUOTE_EXPLAIN, // 견적 안내 문장
    CHAT_ANSWER, // 일반 질문 답변
    REVIEW_SUMMARY // 리뷰 한 줄 요약
}
