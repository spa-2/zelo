package com.server.zelo.ai.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.server.zelo.ai.eval.AiEvalReport.Kind;
import com.server.zelo.ai.eval.AiEvalReport.Result;
import com.server.zelo.ai.gateway.LlmErrorClassifier;
import com.server.zelo.ai.gateway.LlmGateway;
import com.server.zelo.ai.gateway.LlmResult;
import com.server.zelo.ai.gateway.ResilientLlmGateway;
import com.server.zelo.ai.plan.MenuPlan;
import com.server.zelo.ai.plan.MenuPlanIngredient;
import com.server.zelo.ai.plan.QuoteEditPlan;
import com.server.zelo.ai.prompt.MenuPlanPrompt;
import com.server.zelo.ai.prompt.QuoteEditPrompt;
import com.server.zelo.domain.product.entity.BaseUnit;
import com.server.zelo.domain.product.service.ProductService;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

// 메뉴 계획·견적 수정 계획을 평가 문장으로 실제 OpenAI에 보내 정답률, 검증 실패율, 응답 시간, 건당 비용을 잰다.
// 앱과 같은 프롬프트·모델·시간 제한·출력 상한을 쓰되, 재시도가 없는 실제 구현(SpringAiLlmGateway)을 불러 첫 답을 그대로 채점한다.
// 실제 OpenAI를 (메뉴 계획 문장 + 수정 문장) × 반복 횟수만큼 부르므로 ZELO_BENCHMARK=true일 때만 돈다.
// 반복 횟수는 ZELO_EVAL_ROUNDS(기본 3)로 바꾼다. 재료 목록을 로컬 DB에서 읽으므로 상품 시드가 필요하다
@DisabledIfEnvironmentVariable(named = "CI", matches = "true")
@EnabledIfEnvironmentVariable(named = "ZELO_BENCHMARK", matches = "true")
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = "sk-.+")
@SpringBootTest(properties = "zelo.llm.mode=real")
class AiEvalTest {

    private static final int DEFAULT_ROUNDS = 3;
    private static final Path REPORT_DIR = Path.of("local-notes/benchmark");

    @Autowired
    @Qualifier(ResilientLlmGateway.DELEGATE)
    private LlmGateway llmGateway;

    @Autowired
    private ProductService productService;

    @Autowired
    private MenuPlanPrompt menuPlanPrompt;

    @Autowired
    private QuoteEditPrompt quoteEditPrompt;

    @Test
    void 메뉴_계획과_견적_수정_계획의_정답률_응답_시간_비용을_잰다() {
        // given
        int rounds = Integer.parseInt(System.getenv().getOrDefault("ZELO_EVAL_ROUNDS", String.valueOf(DEFAULT_ROUNDS)));
        Map<String, BaseUnit> ingredientUnits = productService.findIngredientUnits();
        assertThat(ingredientUnits).as("로컬 DB에 상품 시드가 없음").isNotEmpty();
        int total = rounds * (MenuPlanEvalCase.ALL.size() + QuoteEditEvalCase.ALL.size());
        List<Result> results = new ArrayList<>();

        // when: 시간대에 따른 OpenAI 속도 변화가 한 묶음에 몰리지 않게 반복마다 전체 문장을 한 번씩 돈다
        for (int round = 1; round <= rounds; round++) {
            for (MenuPlanEvalCase evalCase : MenuPlanEvalCase.ALL) {
                results.add(print(results.size() + 1, total, planMenu(evalCase, ingredientUnits, round)));
            }
            for (QuoteEditEvalCase evalCase : QuoteEditEvalCase.ALL) {
                results.add(print(results.size() + 1, total, planQuoteEdit(evalCase, ingredientUnits, round)));
            }
        }

        // then
        AiEvalReport report = new AiEvalReport(results, rounds);
        System.out.println(report.render());
        System.out.println("[AI 평가] 결과 파일: " + report.write(REPORT_DIR).toAbsolutePath());
        assertThat(results).hasSize(total);
    }

    // 앱의 planMenu와 같이 현재·만료 견적 메뉴를 프롬프트에 넣고 앞 대화를 기록으로 보낸다
    private Result planMenu(MenuPlanEvalCase evalCase, Map<String, BaseUnit> ingredientUnits, int round) {
        String systemPrompt = menuPlanPrompt.systemPrompt(ingredientUnits, evalCase.currentMenuName(),
                evalCase.expiredMenuName());
        return measure(evalCase.id(), evalCase.group(), Kind.MENU_PLAN, evalCase.text(), round,
                () -> llmGateway.planMenu(systemPrompt, evalCase.history(), evalCase.text(), ingredientUnits),
                plan -> plan.findInvalidField(ingredientUnits),
                plan -> evalCase.mismatches(plan, ingredientUnits),
                AiEvalTest::describe);
    }

    private Result planQuoteEdit(QuoteEditEvalCase evalCase, Map<String, BaseUnit> ingredientUnits, int round) {
        String systemPrompt = quoteEditPrompt.systemPrompt(evalCase.quote(), ingredientUnits);
        return measure(evalCase.id(), evalCase.group(), Kind.QUOTE_EDIT, evalCase.text(), round,
                () -> llmGateway.planQuoteEdit(systemPrompt, evalCase.history(), evalCase.text(), ingredientUnits),
                plan -> invalidField(plan, ingredientUnits),
                evalCase::mismatches,
                QuoteEditEvalCase::describe);
    }

    private static <T> Result measure(String caseId, AiEvalReport.Group group, Kind kind, String text, int round,
                                      Supplier<LlmResult<T>> call, Function<T, Optional<String>> invalidField,
                                      Function<T, List<String>> mismatches, Function<T, String> describe) {
        long start = System.nanoTime();
        LlmResult<T> result;
        try {
            result = call.get();
        } catch (Exception e) {
            return new Result(caseId, group, kind, text, round, elapsedMillis(start),
                    LlmErrorClassifier.classify(e).getCode(), null, List.of(), e.getClass().getSimpleName(), null, null, null);
        }
        long latencyMs = elapsedMillis(start);
        T content = result.content();
        String invalid = invalidField.apply(content).orElse(null);
        // 검증에 걸린 답은 채점하지 않는다(필수 칸이 비어 있을 수 있음). 앱은 이 답을 버리고 재시도한다
        List<String> wrong = invalid == null ? score(content, mismatches) : List.of();
        return new Result(caseId, group, kind, text, round, latencyMs, null, invalid, wrong, describe.apply(content),
                result.model(), result.promptTokens(), result.completionTokens());
    }

    // 검증이 보지 않는 칸(conditions 등)이 비어 오면 채점 중 예외가 나므로, 한 건 때문에 전체 실행이 멈추지 않게 오답으로 적는다
    private static <T> List<String> score(T content, Function<T, List<String>> mismatches) {
        try {
            return mismatches.apply(content);
        } catch (RuntimeException e) {
            return List.of("answer: 채점 가능한 답 → " + e.getClass().getSimpleName());
        }
    }

    // QuoteEditPlan.validate는 실패 칸을 돌려주지 않아, 앱과 같은 검증을 부르고 걸리면 "format"으로 적는다
    private static Optional<String> invalidField(QuoteEditPlan plan, Map<String, BaseUnit> ingredientUnits) {
        try {
            plan.validate(ingredientUnits);
            return Optional.empty();
        } catch (RuntimeException e) {
            return Optional.of("format");
        }
    }

    private static String describe(MenuPlan plan) {
        String ingredients = plan.ingredients() == null ? "-" : plan.ingredients().stream()
                .map(MenuPlanIngredient::name).toList().toString();
        return plan.intent() + " " + plan.menuName() + " " + plan.servings() + "인분 예산=" + plan.budget()
                + " 조건=" + plan.conditions() + " 그 밖=" + plan.unmatchedConditions() + " 재료=" + ingredients;
    }

    // 수십 분 걸리므로 호출마다 한 줄씩 찍어 멈췄는지 도는지 바로 알 수 있게 한다
    private static Result print(int index, int total, Result result) {
        String outcome = result.correct() ? "정답" : "오답 " + String.join("; ", AiEvalReport.reasons(result));
        System.out.printf("[AI 평가] %d/%d %d회 %-24s %.1fs %s%n", index, total, result.round(), result.caseId(),
                result.latencyMs() / 1000.0, outcome);
        return result;
    }

    private static long elapsedMillis(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}
