package com.server.zelo.ai.llm;

public interface LlmGateway {

    LlmReply explain(String systemPrompt, String userText);

    // 사용자 말을 장보기 메모(MenuPlan)로 정리한다. JSON 응답을 객체로 바꾸는 것까지 한다
    LlmResult<MenuPlan> planMenu(String systemPrompt, String userText);

    // 한 상품의 리뷰 목록을 한 줄로 요약한다. 채팅 요청이 아니라 미리 만들어 저장해 두는 용도다
    LlmReply summarizeReviews(String systemPrompt, String reviewText);
}
