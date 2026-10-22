package com.server.zelo.ai.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openai.errors.BadRequestException;
import com.openai.errors.InternalServerException;
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
    void 정상_모드면_고정된_리뷰_요약을_반환한다() {
        // when
        LlmReply reply = fakeLlmGateway.summarizeReviews("system", "상품: 백김치");

        // then
        assertThat(reply.text()).isEqualTo(FakeLlmGateway.REVIEW_SUMMARY);
        assertThat(reply.model()).isEqualTo(FakeLlmGateway.MODEL);
    }

    @Test
    void 실패_모드면_리뷰_요약도_서버_오류를_던진다() {
        // given
        fakeLlmGateway.changeMode(FakeLlmGateway.Mode.FAIL);

        // when & then
        assertThatThrownBy(() -> fakeLlmGateway.summarizeReviews("system", "상품: 백김치"))
                .isInstanceOf(InternalServerException.class);
    }

    @Test
    void 실패_모드면_재시도_대상인_서버_오류를_던진다() {
        // given
        fakeLlmGateway.changeMode(FakeLlmGateway.Mode.FAIL);

        // when & then
        assertThatThrownBy(() -> fakeLlmGateway.explain("system", "질문"))
                .isInstanceOfSatisfying(InternalServerException.class,
                        exception -> assertThat(exception.statusCode()).isEqualTo(500))
                .satisfies(exception -> assertThat(LlmErrorClassifier.isRetryable(exception)).isTrue());
    }

    @Test
    void 거절_모드면_재시도하지_않는_요청_오류를_던진다() {
        // given
        fakeLlmGateway.changeMode(FakeLlmGateway.Mode.REJECT);

        // when & then
        assertThatThrownBy(() -> fakeLlmGateway.explain("system", "질문"))
                .isInstanceOf(BadRequestException.class)
                .satisfies(exception -> assertThat(LlmErrorClassifier.isRetryable(exception)).isFalse());
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

    @Test
    void 메뉴_계획은_검증을_통과하는_고정_메모를_돌려준다() {
        // when
        LlmResult<MenuPlan> result = fakeLlmGateway.planMenu("system", "김치볶음밥 재료");

        // then
        assertThat(result.model()).isEqualTo(FakeLlmGateway.MODEL);
        assertThat(result.content().intent()).isEqualTo(MenuPlanIntent.SHOPPING_REQUEST);
        assertThatCode(() -> result.content().validate(MenuPlanPrompt.INGREDIENT_UNITS)).doesNotThrowAnyException();
    }

    @Test
    void 메뉴_계획도_실패_모드면_서버_오류를_던진다() {
        // given
        fakeLlmGateway.changeMode(FakeLlmGateway.Mode.FAIL);

        // when & then
        assertThatThrownBy(() -> fakeLlmGateway.planMenu("system", "질문"))
                .isInstanceOf(InternalServerException.class);
    }
}
