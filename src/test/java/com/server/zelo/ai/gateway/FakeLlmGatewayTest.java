package com.server.zelo.ai.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openai.errors.BadRequestException;
import com.openai.errors.InternalServerException;
import com.server.zelo.ai.plan.MenuPlan;
import com.server.zelo.ai.plan.MenuPlanIntent;
import com.server.zelo.ai.plan.QuoteEdit;
import com.server.zelo.ai.plan.QuoteEditAction;
import com.server.zelo.ai.plan.QuoteEditPlan;
import com.server.zelo.ai.prompt.QuoteExplainPrompt;
import com.server.zelo.domain.product.entity.BaseUnit;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.test.util.ReflectionTestUtils;

class FakeLlmGatewayTest {

    // 테스트에서 쓰는 재료만 담은 재료 목록 (실제로는 재료 테이블에서 읽는다)
    private static final Map<String, BaseUnit> INGREDIENT_UNITS = Map.of(
            "김치", BaseUnit.G, "밥", BaseUnit.G, "계란", BaseUnit.EA, "대파", BaseUnit.BUNCH);

    private final FakeLlmGateway fakeLlmGateway = new FakeLlmGateway();

    @Test
    void 정상_모드면_고정된_답변을_반환한다() {
        // given
        String userText = "김치볶음밥 재료";

        // when
        LlmReply reply = fakeLlmGateway.explain("system", List.of(), userText);

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
        assertThatThrownBy(() -> fakeLlmGateway.explain("system", List.of(), "질문"))
                .isInstanceOfSatisfying(InternalServerException.class,
                        exception -> assertThat(exception.statusCode()).isEqualTo(500))
                .satisfies(exception -> assertThat(LlmErrorClassifier.isRetryable(exception)).isTrue());
    }

    @Test
    void 거절_모드면_재시도하지_않는_요청_오류를_던진다() {
        // given
        fakeLlmGateway.changeMode(FakeLlmGateway.Mode.REJECT);

        // when & then
        assertThatThrownBy(() -> fakeLlmGateway.explain("system", List.of(), "질문"))
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
        LlmReply reply = fakeLlmGateway.explain("system", List.of(), "질문");

        // then
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(300));
        assertThat(reply.text()).startsWith("[FAKE]");
    }

    @Test
    void 최대_지연을_두면_최소와_최대_사이만큼_늦게_응답한다() {
        // given
        fakeLlmGateway.changeMode(FakeLlmGateway.Mode.DELAY);
        fakeLlmGateway.changeDelay(Duration.ofMillis(100));
        fakeLlmGateway.changeDelayMax(Duration.ofMillis(200));
        long start = System.nanoTime();

        // when
        fakeLlmGateway.explain("system", List.of(), "질문");

        // then: 위쪽은 스레드 깨어나는 시간이 더해져 넉넉하게 본다
        assertThat(Duration.ofNanos(System.nanoTime() - start))
                .isGreaterThanOrEqualTo(Duration.ofMillis(100))
                .isLessThan(Duration.ofSeconds(1));
    }

    @Test
    void 최대_지연이_최소보다_작으면_최소_지연으로_고정한다() {
        // given
        fakeLlmGateway.changeMode(FakeLlmGateway.Mode.DELAY);
        fakeLlmGateway.changeDelay(Duration.ofMillis(200));
        fakeLlmGateway.changeDelayMax(Duration.ofMillis(100));
        long start = System.nanoTime();

        // when
        fakeLlmGateway.explain("system", List.of(), "질문");

        // then
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(200));
    }

    @Test
    void 메뉴_계획은_기본으로_일반_질문을_돌려준다() {
        // when: 채팅이 기본으로는 지금처럼 답변만 하도록
        LlmResult<MenuPlan> result = fakeLlmGateway.planMenu("system", List.of(), "김치볶음밥 재료", INGREDIENT_UNITS);

        // then
        assertThat(result.model()).isEqualTo(FakeLlmGateway.MODEL);
        assertThat(result.content().intent()).isEqualTo(MenuPlanIntent.GENERAL_QUESTION);
        assertThatCode(() -> result.content().validate(INGREDIENT_UNITS)).doesNotThrowAnyException();
    }

    @Test
    void 장보기_요청으로_바꾸면_검증을_통과하는_고정_메모를_돌려준다() {
        // given
        fakeLlmGateway.changePlanIntent(MenuPlanIntent.SHOPPING_REQUEST);

        // when
        LlmResult<MenuPlan> result = fakeLlmGateway.planMenu("system", List.of(), "김치볶음밥 재료", INGREDIENT_UNITS);

        // then
        assertThat(result.content().intent()).isEqualTo(MenuPlanIntent.SHOPPING_REQUEST);
        assertThat(result.content().ingredients()).isNotEmpty();
        assertThatCode(() -> result.content().validate(INGREDIENT_UNITS)).doesNotThrowAnyException();
    }

    @Test
    void 견적_수정으로_바꾸면_빈_칸의_견적_수정_메모를_돌려준다() {
        // given
        fakeLlmGateway.changePlanIntent(MenuPlanIntent.QUOTE_EDIT);

        // when
        LlmResult<MenuPlan> result = fakeLlmGateway.planMenu("system", List.of(), "계란 빼줘", INGREDIENT_UNITS);

        // then
        assertThat(result.content().intent()).isEqualTo(MenuPlanIntent.QUOTE_EDIT);
        assertThatCode(() -> result.content().validate(INGREDIENT_UNITS)).doesNotThrowAnyException();
    }

    @Test
    void 수정_계획은_기본으로_견적을_고치지_않고_되묻는_계획을_돌려준다() {
        // when
        LlmResult<QuoteEditPlan> result = fakeLlmGateway.planQuoteEdit("system", List.of(), "김치 빼줘", INGREDIENT_UNITS);

        // then
        assertThat(result.model()).isEqualTo(FakeLlmGateway.MODEL);
        assertThat(result.content().edits()).isEmpty();
        assertThat(result.content().question()).isEqualTo(FakeLlmGateway.EDIT_QUESTION);
    }

    @Test
    void 수정_계획을_바꾸면_그_계획을_돌려준다() {
        // given
        QuoteEditPlan editPlan = QuoteEditPlan.of(
                List.of(QuoteEdit.of(QuoteEditAction.REMOVE, 1L, 0, "", 0, BaseUnit.EA)), "");
        fakeLlmGateway.changeQuoteEditPlan(editPlan);

        // when
        LlmResult<QuoteEditPlan> result = fakeLlmGateway.planQuoteEdit("system", List.of(), "김치 빼줘", INGREDIENT_UNITS);

        // then
        assertThat(result.content()).isEqualTo(editPlan);
    }

    @Test
    void 수정_계획도_실패_모드면_서버_오류를_던진다() {
        // given
        fakeLlmGateway.changeMode(FakeLlmGateway.Mode.FAIL);

        // when & then
        assertThatThrownBy(() -> fakeLlmGateway.planQuoteEdit("system", List.of(), "김치 빼줘", INGREDIENT_UNITS))
                .isInstanceOf(InternalServerException.class);
    }

    @Test
    void 메뉴_계획도_실패_모드면_서버_오류를_던진다() {
        // given
        fakeLlmGateway.changeMode(FakeLlmGateway.Mode.FAIL);

        // when & then
        assertThatThrownBy(() -> fakeLlmGateway.planMenu("system", List.of(), "질문", INGREDIENT_UNITS))
                .isInstanceOf(InternalServerException.class);
    }

    @Test
    void 작업별_지연을_주면_그_작업만_그_시간만큼_늦게_응답한다() {
        // given: 다른 작업은 기본 지연(0초)을 쓴다
        fakeLlmGateway.changeMode(FakeLlmGateway.Mode.DELAY);
        fakeLlmGateway.changeDelay(Duration.ZERO);
        fakeLlmGateway.changeOperationDelays("MENU_PLAN:300ms");

        // when
        long planStart = System.nanoTime();
        fakeLlmGateway.planMenu("system", List.of(), "김치볶음밥 재료", INGREDIENT_UNITS);
        Duration planElapsed = Duration.ofNanos(System.nanoTime() - planStart);
        long answerStart = System.nanoTime();
        fakeLlmGateway.explain("system", List.of(), "질문");
        Duration answerElapsed = Duration.ofNanos(System.nanoTime() - answerStart);

        // then
        assertThat(planElapsed).isGreaterThanOrEqualTo(Duration.ofMillis(300));
        assertThat(answerElapsed).isLessThan(Duration.ofMillis(200));
    }

    @Test
    void 작업별_지연에_범위를_주면_최소와_최대_사이만큼_늦게_응답한다() {
        // given
        fakeLlmGateway.changeMode(FakeLlmGateway.Mode.DELAY);
        fakeLlmGateway.changeDelay(Duration.ZERO);
        fakeLlmGateway.changeOperationDelays("QUOTE_EDIT_PLAN:100ms-200ms");
        long start = System.nanoTime();

        // when
        fakeLlmGateway.planQuoteEdit("system", List.of(), "계란 빼줘", INGREDIENT_UNITS);

        // then: 위쪽은 스레드 깨어나는 시간이 더해져 넉넉하게 본다
        assertThat(Duration.ofNanos(System.nanoTime() - start))
                .isGreaterThanOrEqualTo(Duration.ofMillis(100))
                .isLessThan(Duration.ofSeconds(1));
    }

    @Test
    void 견적_안내와_일반_답변은_system_프롬프트로_나눠_각자의_지연을_쓴다() {
        // given
        QuoteExplainPrompt quoteExplainPrompt = new QuoteExplainPrompt(new ByteArrayResource("견적 안내 규칙".getBytes()));
        ReflectionTestUtils.setField(fakeLlmGateway, "quoteExplainPrompt", quoteExplainPrompt);
        fakeLlmGateway.changeMode(FakeLlmGateway.Mode.DELAY);
        fakeLlmGateway.changeDelay(Duration.ZERO);
        fakeLlmGateway.changeOperationDelays("QUOTE_EXPLAIN:300ms");

        // when
        long explainStart = System.nanoTime();
        fakeLlmGateway.explain(quoteExplainPrompt.systemPrompt(), List.of(), "메뉴: 김치볶음밥");
        Duration explainElapsed = Duration.ofNanos(System.nanoTime() - explainStart);
        long answerStart = System.nanoTime();
        fakeLlmGateway.explain("일반 답변 규칙", List.of(), "질문");
        Duration answerElapsed = Duration.ofNanos(System.nanoTime() - answerStart);

        // then
        assertThat(explainElapsed).isGreaterThanOrEqualTo(Duration.ofMillis(300));
        assertThat(answerElapsed).isLessThan(Duration.ofMillis(200));
    }

    @Test
    void 의도_단어가_든_문장은_그_의도로_정리하고_없으면_기본_의도를_쓴다() {
        // given
        fakeLlmGateway.changePlanIntent(MenuPlanIntent.SHOPPING_REQUEST);
        fakeLlmGateway.changeIntentKeywords("QUOTE_EDIT:빼 줘|빼줘, LOOKUP_QUESTION:리뷰, GENERAL_QUESTION:보관");

        // when
        MenuPlan edit = fakeLlmGateway.planMenu("system", List.of(), "계란은 빼 줘", INGREDIENT_UNITS).content();
        MenuPlan lookup = fakeLlmGateway.planMenu("system", List.of(), "이 김치 리뷰 어때?", INGREDIENT_UNITS).content();
        MenuPlan general = fakeLlmGateway.planMenu("system", List.of(), "남은 김치볶음밥은 어떻게 보관해?", INGREDIENT_UNITS).content();
        MenuPlan shopping = fakeLlmGateway.planMenu("system", List.of(), "4인분으로 해 줘", INGREDIENT_UNITS).content();

        // then
        assertThat(edit.intent()).isEqualTo(MenuPlanIntent.QUOTE_EDIT);
        assertThat(lookup.intent()).isEqualTo(MenuPlanIntent.LOOKUP_QUESTION);
        assertThat(general.intent()).isEqualTo(MenuPlanIntent.GENERAL_QUESTION);
        assertThat(shopping.intent()).isEqualTo(MenuPlanIntent.SHOPPING_REQUEST);
        assertThat(shopping.ingredients()).isNotEmpty();
        assertThatCode(() -> edit.validate(INGREDIENT_UNITS)).doesNotThrowAnyException();
    }

    @Test
    void 의도_단어가_여러_규칙에_맞으면_먼저_적은_규칙을_따른다() {
        // given: "리뷰 나쁜 계란은 빼 줘"는 리뷰를 말해도 견적 수정이다 (menu-plan.md 규칙)
        fakeLlmGateway.changeIntentKeywords("QUOTE_EDIT:빼 줘, LOOKUP_QUESTION:리뷰");

        // when
        MenuPlan menuPlan = fakeLlmGateway.planMenu("system", List.of(), "리뷰 나쁜 계란은 빼 줘", INGREDIENT_UNITS).content();

        // then
        assertThat(menuPlan.intent()).isEqualTo(MenuPlanIntent.QUOTE_EDIT);
    }
}
