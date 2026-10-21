package com.server.zelo.ai.llm;

public interface LlmGateway {

    LlmReply explain(String systemPrompt, String userText);

    // 사용자 말을 장보기 메모(MenuPlan)로 정리한다. JSON 응답을 객체로 바꾸는 것까지 한다
    LlmResult<MenuPlan> planMenu(String systemPrompt, String userText);
}
