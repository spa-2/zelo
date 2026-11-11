package com.server.zelo.ai.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

// 실제 OpenAI를 호출해 요금이 나가므로, 키가 있는 로컬에서만 직접 실행한다.
// CI는 저장소에 진짜 키가 있어도 돌리지 않는다(GitHub Actions는 CI=true를 넣는다)
// 이 테스트는 연결과 토큰 사용량을 확인하는 것이라, 실제 응답(약 10초)이 시도당 시간 제한(5초)에 끊기지 않게 넉넉히 준다
@DisabledIfEnvironmentVariable(named = "CI", matches = "true")
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = "sk-.+")
@SpringBootTest(properties = {"zelo.llm.mode=real", "zelo.llm.timeout=30s"})
class SpringAiLlmGatewayTest {

    // 재시도·서킷 브레이커로 감싸기 전의 실제 구현만 검증한다
    @Autowired
    @Qualifier(ResilientLlmGateway.DELEGATE)
    private LlmGateway llmGateway;

    @Test
    void 실제_LLM에게_질문하면_답변과_토큰_사용량이_온다() {
        // given
        String systemPrompt = "너는 장보기 도우미야. 한 문장으로 답해.";

        // when
        LlmReply reply = llmGateway.explain(systemPrompt, List.of(), "김치볶음밥에 뭐가 들어가?");

        // then
        assertThat(reply.text()).isNotBlank();
        assertThat(reply.promptTokens()).isPositive();
        assertThat(reply.completionTokens()).isPositive();
    }
}
