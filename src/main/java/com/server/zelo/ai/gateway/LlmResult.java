package com.server.zelo.ai.gateway;

// 구조화 출력처럼 문장이 아닌 결과를 받을 때, 결과와 함께 모델·토큰 수를 담아 호출 로그에 남길 수 있게 한다
public record LlmResult<T>(
        T content, // LLM 응답을 변환한 결과 (예: MenuPlan)
        String model,
        Integer promptTokens,
        Integer completionTokens
) {

    public static <T> LlmResult<T> of(T content, String model, Integer promptTokens, Integer completionTokens) {
        return new LlmResult<>(content, model, promptTokens, completionTokens);
    }
}
