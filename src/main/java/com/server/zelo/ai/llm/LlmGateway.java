package com.server.zelo.ai.llm;

public interface LlmGateway {

    LlmReply explain(String systemPrompt, String userText);
}
