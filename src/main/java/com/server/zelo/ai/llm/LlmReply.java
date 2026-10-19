package com.server.zelo.ai.llm;

public record LlmReply(
        String text, // AI가 한 말
        String model, // 어떤 모델이 답을 한 것인지
        Integer promptTokens, // 우리가 보낸 글의 토큰 수
        Integer completionTokens // AI가 쓴 글의 토큰 수
) {

    public static LlmReply of(String text, String model, Integer promptTokens, Integer completionTokens) {
        return new LlmReply(text, model, promptTokens, completionTokens);
    }
}
