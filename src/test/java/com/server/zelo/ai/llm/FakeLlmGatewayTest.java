package com.server.zelo.ai.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class FakeLlmGatewayTest {

    private final FakeLlmGateway fakeLlmGateway = new FakeLlmGateway();

    @Test
    void 정상_모드면_고정된_답변을_반환한다() {
        // given
        String userText = "김치볶음밥 재료";

        // when
        LlmReply reply = fakeLlmGateway.explain("system", userText);

        // then
        assertThat(reply.text()).isEqualTo("[FAKE] 김치볶음밥 재료에 대한 답변입니다.");
        assertThat(reply.model()).isEqualTo(FakeLlmGateway.MODEL);
    }

    @Test
    void 실패_모드면_예외가_발생한다() {
        // given
        fakeLlmGateway.changeMode(FakeLlmGateway.Mode.FAIL);

        // when & then
        assertThatThrownBy(() -> fakeLlmGateway.explain("system", "질문"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 지연_모드면_설정한_시간만큼_늦게_응답한다() {
        // given
        fakeLlmGateway.changeMode(FakeLlmGateway.Mode.DELAY);
        fakeLlmGateway.changeDelay(Duration.ofMillis(300));
        long start = System.nanoTime();

        // when
        LlmReply reply = fakeLlmGateway.explain("system", "질문");

        // then
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(300));
        assertThat(reply.text()).startsWith("[FAKE]");
    }
}
