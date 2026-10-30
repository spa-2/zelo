package com.server.zelo.common.monitoring;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// 비즈니스 지표 이름은 요기 한곳에서만 정함. 파트마다 이름이 제각각이면 대시보드에서 못 묶음
// 태그 값은 사유 코드처럼 개수가 정해진 값만 씀 (userId, orderId 같은 값을 넣으면 지표가 끝없이 늘어남)
@Getter
@RequiredArgsConstructor
public enum BusinessMetric {

    //TODO 이름만 미리 만들어 둔거니 각자 가져가서 붙히시면 됩니다~!

    // 견적
    QUOTE_CREATED("zelo.quote.created", "견적 발급 수"),
    QUOTE_EXCLUDED("zelo.quote.excluded", "견적에서 빠진 품목 수 (reason)"),
    QUOTE_CREATE_TIME("zelo.quote.create.time", "견적 발급 시간"),

    // 주문
    ORDER_CREATED("zelo.order.created", "주문 생성 수"),
    ORDER_REJECTED("zelo.order.rejected", "주문 거부 수 (reason)"),

    // 결제
    PAYMENT_RESULT("zelo.payment.result", "결제 결과 수 (status)"),

    // LLM. outcome은 SUCCESS 또는 실패 코드(AI_xxx)
    LLM_CALL("zelo.llm.call", "LLM 호출 1건 시간, 재시도 포함 (operation, outcome)"),
    LLM_ATTEMPT("zelo.llm.attempt", "LLM 시도 1번 시간 (operation, outcome)"),
    LLM_TOKENS("zelo.llm.tokens", "LLM 토큰 수 (operation, model, type)"),
    LLM_TIME_BUDGET_EXCEEDED("zelo.llm.time.budget.exceeded", "메시지 시간 예산이 부족해 건너뛰거나 짧게 끊은 LLM 시도 수 (operation, stage)"),

    // 채팅
    CHAT_REPLY("zelo.chat.reply", "채팅 답변 1건 처리 시간 (intent, outcome)"),
    CHAT_FALLBACK("zelo.chat.fallback", "LLM 실패로 저장한 FALLBACK 메시지 수 (code)"),
    CHAT_RETRY("zelo.chat.retry", "FALLBACK 다시 시도 결과 수 (result)"),
    CHAT_IDEMPOTENCY("zelo.chat.idempotency", "멱등 키 처리 결과 수 (result)"),
    CHAT_IDEMPOTENCY_STORE_FAILURE("zelo.chat.idempotency.store.failure", "멱등 기록 저장·삭제 실패 수 (operation)"),
    CHAT_RATE_LIMITED("zelo.chat.rate.limited", "사용자별 메시지 횟수 제한으로 거절한 수"),
    CHAT_QUOTE_BUSY("zelo.chat.quote.busy", "견적 락을 기다리다 포기한 수"),
    CHAT_QUOTE_EDIT("zelo.chat.quote.edit", "채팅의 견적 수정 결과 수 (source, result)"),
    CHAT_EXPLAIN_DEGRADED("zelo.chat.explain.degraded", "안내 문장을 받지 못해 고정 문구로 저장한 견적 카드 수");

    private final String key;
    private final String description;
}