package com.server.zelo.ai.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

// 실제 OpenAI를 호출해 요금이 나가므로, 키가 있는 로컬에서만 직접 실행한다 (CI의 dummy 키로는 돌지 않게 sk- 접두사로 판별)
@SpringBootTest(properties = "zelo.llm.mode=real")
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = "sk-.+")
class SpringAiLlmGatewayManualTest {

    @Autowired
    private LlmGateway llmGateway;

    @Test
    void 실제_LLM에게_질문하면_답변과_토큰_사용량이_온다() {
        // given
        String systemPrompt = "너는 장보기 도우미야. 한 문장으로 답해.";

        // when
        LlmReply reply = llmGateway.explain(systemPrompt, "김치볶음밥에 뭐가 들어가?");

        // then
        assertThat(reply.text()).isNotBlank();
        assertThat(reply.promptTokens()).isPositive();
        assertThat(reply.completionTokens()).isPositive();
    }
}
