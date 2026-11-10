package com.server.zelo.domain.quote.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.BDDMockito.given;

import com.server.zelo.ai.plan.MenuPlan;
import com.server.zelo.ai.plan.MenuPlanIngredient;
import com.server.zelo.ai.plan.MenuPlanIntent;
import com.server.zelo.common.monitoring.BusinessMetrics;
import com.server.zelo.domain.inventory.service.InventoryService;
import com.server.zelo.domain.product.entity.BaseUnit;
import com.server.zelo.domain.product.entity.Category;
import com.server.zelo.domain.product.entity.Keyword;
import com.server.zelo.domain.product.entity.Product;
import com.server.zelo.domain.product.service.ProductService;
import com.server.zelo.domain.quote.model.BudgetOption;
import com.server.zelo.domain.quote.model.ExcludedItem;
import com.server.zelo.domain.quote.model.Quote;
import com.server.zelo.domain.quote.model.QuoteItem;
import com.server.zelo.domain.quote.model.SubstituteItem;
import com.server.zelo.domain.quote.repository.QuoteRepository;
import com.server.zelo.domain.quote.service.QuoteCommandService;
import com.server.zelo.domain.review.service.ReviewService;
import com.server.zelo.domain.user.service.UserBlockedCategoryService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 견적 품질 평가. LLM 없이 정해 둔 메뉴 계획을 운영 시드 카탈로그(상품 825개, 리뷰 3,525개)에 넣고 견적을 매긴다.
 * 정답을 사람이 적지 않고, 카탈로그에서 "고를 수 있었던 최선"을 계산해 실제 견적과 비교한다 (사람이 정답을 잘못 적을 일이 없음).
 * 결과는 build/quote-eval/report.md에 남기고, 지켜야 할 기준이 깨지면 실패한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QuoteQualityEvalTest {

    private static final SeedCatalog CATALOG = new SeedCatalog();
    private static final Path REPORT = Path.of("build", "quote-eval", "report.md");

    @Mock
    private ReviewService reviewService;

    @Mock
    private ProductService productService;

    @Mock
    private QuoteRepository quoteRepository;

    @Mock
    private InventoryService inventoryService;

    @Mock
    private UserBlockedCategoryService userBlockedCategoryService;

    @Spy
    private BusinessMetrics businessMetrics = new BusinessMetrics(new SimpleMeterRegistry());

    @InjectMocks
    private QuoteCommandService quoteCommandService;

    // 사용자 ID별로 막은 카테고리. 케이스마다 사용자 ID를 다르게 준다
    private final Map<Long, Set<Long>> blockedByUser = new HashMap<>();

    record EvalCase(String id, String menu, Integer servings, Long budget, List<Keyword> conditions,
                    List<List<Object>> ingredients, List<String> blockedCategories) {
    }

    record Baseline(double excessShare, double excessP90) {
    }

    // 재료 한 줄의 평가 결과. picked가 null이면 견적에 안 담긴 줄
    record Line(String caseId, String ingredient, Set<Keyword> conditions, Product picked, int quantity, int required,
                Set<Long> blocked, String actualExclusion) {
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void stubCatalog() {
        given(productService.findQuoteCandidates(anyMap(), anyCollection()))
                .willAnswer(invocation -> CATALOG.candidates(((Map<String, Integer>) invocation.getArgument(0)).keySet()));
        given(productService.findKeywords(anyCollection()))
                .willAnswer(invocation -> CATALOG.keywords((Collection<Long>) invocation.getArgument(0)));
        given(inventoryService.findAvailableQuantities(anyCollection()))
                .willAnswer(invocation -> CATALOG.stocks((Collection<Long>) invocation.getArgument(0)));
        given(reviewService.findReviewEvidences(anyList(), anySet()))
                .willAnswer(invocation -> CATALOG.reviewEvidences(invocation.getArgument(0), invocation.getArgument(1)));
        given(userBlockedCategoryService.findBlockedCategoryIds(anyLong()))
                .willAnswer(invocation -> blockedByUser.getOrDefault((Long) invocation.getArgument(0), Set.of()));
    }

    @Test
    void 견적_품질을_평가하고_기준이_깨지면_실패한다() {
        List<EvalCase> cases = JsonMapper.builder().build()
                .readValue(SeedCatalog.read("quote-eval/cases.json"), new TypeReference<>() {
                });
        List<Line> lines = new ArrayList<>();
        List<String> caseRows = new ArrayList<>();
        int budgetCases = 0;
        int withinAtFirst = 0;
        int withinAfterOptions = 0;
        int budgetMissed = 0;
        int deterministic = 0;

        long userId = 1;
        for (EvalCase evalCase : cases) {
            Set<Long> blocked = evalCase.blockedCategories() == null ? Set.of() : evalCase.blockedCategories().stream()
                    .map(name -> Objects.requireNonNull(CATALOG.category(name), name).getId())
                    .collect(Collectors.toSet());
            blockedByUser.put(userId, blocked);
            MenuPlan plan = toPlan(evalCase);
            Quote quote = quoteCommandService.createQuote(userId, userId, plan);
            Quote again = quoteCommandService.createQuote(userId, userId, plan);
            if (sameResult(quote, again)) {
                deterministic++;
            }
            List<Line> caseLines = plan.ingredients().stream()
                    .map(ingredient -> toLine(evalCase.id(), ingredient, Set.copyOf(plan.conditions()), quote, blocked))
                    .toList();
            lines.addAll(caseLines);

            String budgetCell = "-";
            if (evalCase.budget() != null) {
                budgetCases++;
                long afterOptions = afterBudgetOptions(quote);
                long lowerBound = caseLines.stream().filter(line -> line.picked() != null && isItem(quote, line.picked()))
                        .mapToLong(this::cheapestKeepingConditions).sum();
                withinAtFirst += quote.totalAmount() <= evalCase.budget() ? 1 : 0;
                withinAfterOptions += afterOptions <= evalCase.budget() ? 1 : 0;
                boolean missed = lowerBound <= evalCase.budget() && afterOptions > evalCase.budget();
                budgetMissed += missed ? 1 : 0;
                budgetCell = "%,d원 → %,d / 예산 %,d (조건 유지 최저 %,d)%s".formatted(quote.totalAmount(), afterOptions,
                        evalCase.budget(), lowerBound, missed ? " ❌" : "");
            }
            caseRows.add("| %s | %s | %s | %d / %d | %s | %,d원 | %s |".formatted(evalCase.id(), evalCase.menu(),
                    plan.conditions().isEmpty() ? "-" : plan.conditions(), quote.items().size(), plan.ingredients().size(),
                    exclusionCell(quote),
                    quote.totalAmount(), budgetCell));
            userId++;
        }

        List<Line> sweep = conditionSweep(userId);

        // 지표 계산
        List<Line> picked = lines.stream().filter(line -> line.picked() != null).toList();
        Ratio bestMatch = ratio(picked, line -> !line.conditions().isEmpty(), this::matchesBest);
        Ratio fullMatch = ratio(picked, this::fullMatchPossible, line -> matched(line).size() == line.conditions().size());
        Ratio sweepBest = ratio(sweep, line -> line.picked() != null, this::matchesBest);
        Ratio sweepFull = ratio(sweep, this::fullMatchPossible, line -> line.picked() != null
                && matched(line).size() == line.conditions().size());
        Ratio exclusion = ratio(lines, line -> true, line -> Objects.equals(expectedExclusion(line), line.actualExclusion()));
        List<Double> overbuys = picked.stream()
                .map(line -> (double) line.quantity() * line.picked().getContentAmount() / line.required()).sorted().toList();
        List<Double> premiums = picked.stream().map(this::premium).sorted().toList();
        List<Double> excess = picked.stream().map(this::excessOverSmallest).sorted().toList();
        double excessShare = (double) excess.stream().filter(value -> value > 1.0).count() / excess.size();
        double overbuyTwiceShare = (double) overbuys.stream().filter(value -> value >= 2.0).count() / overbuys.size();
        Baseline baseline = JsonMapper.builder().build()
                .readValue(SeedCatalog.read("quote-eval/baseline.json"), Baseline.class);

        writeReport(cases.size(), lines.size(), sweep.size(), bestMatch, fullMatch, sweepBest, sweepFull, exclusion,
                overbuys, overbuyTwiceShare, excess, excessShare, premiums, budgetCases, withinAtFirst, withinAfterOptions, budgetMissed,
                deterministic, baseline, caseRows);

        // 지켜야 할 기준 (깨지면 report.md에서 ❌ 줄을 보면 됨)
        assertThat(bestMatch.failed()).as("조건을 가장 많이 맞춘 상품을 못 고른 줄").isEmpty();
        assertThat(sweepBest.failed()).as("조건 조합 전수 검사에서 조건을 가장 많이 맞춘 상품을 못 고른 줄").isEmpty();
        assertThat(exclusion.failed()).as("빠질 이유가 없는데 빠졌거나 사유가 틀린 줄").isEmpty();
        assertThat(deterministic).as("같은 입력이면 같은 견적").isEqualTo(cases.size());
        assertThat(budgetMissed).as("조건을 지키고도 예산 안으로 맞출 수 있었는데 예산 후보로도 못 맞춘 견적").isZero();
        assertThat(excessShare).as("더 적게 살 수 있었는데 더 많이 산 줄 비율 (기준 %.3f)", baseline.excessShare())
                .isLessThanOrEqualTo(baseline.excessShare() + 0.001);
        assertThat(percentile(excess, 0.9)).as("살 수 있었던 최소량 대비 구매량 p90 (기준 %.3f)", baseline.excessP90())
                .isLessThanOrEqualTo(baseline.excessP90() + 0.001);
    }

    // 재료마다, 그 재료 상품에 실제로 붙어 있는 조건 2개 이상 조합을 하나씩 요청해 본다 (상품 하나를 사는 양)
    private List<Line> conditionSweep(long userId) {
        Map<String, List<Product>> byIngredient = CATALOG.products().stream()
                .collect(Collectors.groupingBy(product -> product.getIngredient().getName()));
        List<Line> sweep = new ArrayList<>();
        byIngredient.forEach((name, products) -> {
            Set<Set<Keyword>> combinations = products.stream().map(product -> CATALOG.keywords(product.getId()))
                    .filter(keywords -> keywords.size() >= 2)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            int amount = products.stream().mapToInt(Product::getContentAmount).min().orElseThrow();
            BaseUnit unit = products.get(0).getIngredient().getBaseUnit();
            for (Set<Keyword> conditions : combinations) {
                MenuPlan plan = MenuPlan.of(MenuPlanIntent.SHOPPING_REQUEST, name, 1, null,
                        List.of(MenuPlanIngredient.of(name, amount, unit)), List.copyOf(conditions), List.of());
                Quote quote = quoteCommandService.createQuote(userId, userId, plan);
                sweep.add(toLine("SWEEP", plan.ingredients().get(0), conditions, quote, Set.of()));
            }
        });
        return sweep;
    }

    private MenuPlan toPlan(EvalCase evalCase) {
        List<MenuPlanIngredient> ingredients = evalCase.ingredients().stream()
                .map(row -> MenuPlanIngredient.of((String) row.get(0), ((Number) row.get(1)).intValue(),
                        BaseUnit.valueOf((String) row.get(2))))
                .toList();
        return MenuPlan.of(MenuPlanIntent.SHOPPING_REQUEST, evalCase.menu(), evalCase.servings(), evalCase.budget(),
                ingredients, evalCase.conditions(), List.of());
    }

    // 견적에서 이 재료로 담긴 상품(또는 품절 대체 후보)을 찾는다
    private Line toLine(String caseId, MenuPlanIngredient ingredient, Set<Keyword> conditions, Quote quote, Set<Long> blocked) {
        Optional<QuoteItem> item = quote.items().stream()
                .filter(quoteItem -> ingredientOf(quoteItem).equals(ingredient.name())).findFirst()
                .or(() -> quote.substitutes().stream().filter(substitute -> substitute.name().equals(ingredient.name()))
                        .map(SubstituteItem::item).findFirst());
        String exclusion = quote.excludedItems().stream().filter(excluded -> excluded.name().equals(ingredient.name()))
                .map(ExcludedItem::reason).findFirst().orElse(null);
        return new Line(caseId, ingredient.name(), conditions, item.map(this::product).orElse(null),
                item.map(QuoteItem::quantity).orElse(0), ingredient.amount(), blocked, exclusion);
    }

    // 카탈로그 기준으로 이 줄이 빠져야 하는 사유. 담을 수 있으면 null
    private String expectedExclusion(Line line) {
        List<Product> candidates = CATALOG.candidates(Set.of(line.ingredient()));
        if (candidates.isEmpty()) {
            return "PRODUCT_NOT_FOUND";
        }
        if (!candidates.get(0).getCategory().isAgentOrderable()) {
            return "CATEGORY_NOT_ALLOWED";
        }
        if (allowed(line).isEmpty()) {
            return "BLOCKED_BY_USER";
        }
        return available(line).isEmpty() ? "OUT_OF_STOCK" : null;
    }

    private List<Product> allowed(Line line) {
        return CATALOG.candidates(Set.of(line.ingredient())).stream().filter(product -> {
            Category category = product.getCategory();
            return !line.blocked().contains(category.getId())
                    && (category.getParent() == null || !line.blocked().contains(category.getParent().getId()));
        }).toList();
    }

    private List<Product> available(Line line) {
        return allowed(line).stream()
                .filter(product -> CATALOG.stock(product.getId()) >= quantity(line.required(), product)).toList();
    }

    private boolean matchesBest(Line line) {
        int best = available(line).stream().mapToInt(product -> matchedCount(product, line.conditions())).max().orElse(0);
        return line.picked() != null && matchedCount(line.picked(), line.conditions()) == best;
    }

    private boolean fullMatchPossible(Line line) {
        return !line.conditions().isEmpty() && available(line).stream()
                .anyMatch(product -> matchedCount(product, line.conditions()) == line.conditions().size());
    }

    // 산 양 ÷ 같은 수의 조건을 맞춘 상품으로 살 수 있었던 가장 적은 양 (팩 크기 때문에 어쩔 수 없는 초과는 빼고 본다)
    private double excessOverSmallest(Line line) {
        int matched = matchedCount(line.picked(), line.conditions());
        int smallest = available(line).stream().filter(product -> matchedCount(product, line.conditions()) == matched)
                .mapToInt(product -> quantity(line.required(), product) * product.getContentAmount()).min().orElseThrow();
        return (double) line.quantity() * line.picked().getContentAmount() / smallest;
    }

    // 고른 상품 총액 ÷ 같은 수의 조건을 맞춘 상품 중 가장 싼 총액
    private double premium(Line line) {
        int matched = matchedCount(line.picked(), line.conditions());
        long cheapest = available(line).stream().filter(product -> matchedCount(product, line.conditions()) == matched)
                .mapToLong(product -> lineTotal(line.required(), product)).min().orElseThrow();
        return (double) lineTotal(line.required(), line.picked()) / cheapest;
    }

    // 고른 상품이 맞춘 조건을 다 지키는 상품 중 가장 싼 총액 (예산 후보와 같은 규칙)
    private long cheapestKeepingConditions(Line line) {
        Set<Keyword> matched = matched(line);
        return available(line).stream().filter(product -> CATALOG.keywords(product.getId()).containsAll(matched))
                .mapToLong(product -> lineTotal(line.required(), product)).min().orElseThrow();
    }

    // 사용자가 예산 후보를 모두 눌렀을 때의 합계
    private long afterBudgetOptions(Quote quote) {
        Map<Long, QuoteItem> options = quote.budgetOptions().stream()
                .collect(Collectors.toMap(BudgetOption::productId, BudgetOption::item));
        return quote.items().stream()
                .mapToLong(item -> options.getOrDefault(item.productId(), item).lineAmount()).sum();
    }

    private String exclusionCell(Quote quote) {
        List<String> cells = new ArrayList<>();
        quote.excludedItems().forEach(item -> cells.add(item.name() + ":" + item.reason()));
        quote.substitutes().forEach(substitute -> cells.add(substitute.name() + ":품절 대체"));
        return cells.isEmpty() ? "-" : String.join(", ", cells);
    }

    private boolean sameResult(Quote first, Quote second) {
        return summary(first).equals(summary(second));
    }

    private List<String> summary(Quote quote) {
        List<String> summary = new ArrayList<>();
        quote.items().forEach(item -> summary.add(item.productId() + "x" + item.quantity()));
        quote.substitutes().forEach(substitute -> summary.add("S" + substitute.item().productId()));
        quote.excludedItems().forEach(excluded -> summary.add(excluded.name() + ":" + excluded.reason()));
        quote.budgetOptions().forEach(option -> summary.add(option.productId() + ">" + option.item().productId()));
        return summary;
    }

    private boolean isItem(Quote quote, Product product) {
        return quote.items().stream().anyMatch(item -> item.productId().equals(product.getId()));
    }

    private Set<Keyword> matched(Line line) {
        Set<Keyword> matched = new HashSet<>(CATALOG.keywords(line.picked().getId()));
        matched.retainAll(line.conditions());
        return matched;
    }

    private int matchedCount(Product product, Set<Keyword> conditions) {
        return (int) CATALOG.keywords(product.getId()).stream().filter(conditions::contains).count();
    }

    private String ingredientOf(QuoteItem item) {
        return product(item).getIngredient().getName();
    }

    private Product product(QuoteItem item) {
        return CATALOG.products().get(item.productId().intValue() - 1);
    }

    private static int quantity(int required, Product product) {
        return (required + product.getContentAmount() - 1) / product.getContentAmount();
    }

    private static long lineTotal(int required, Product product) {
        return product.getPrice() * quantity(required, product);
    }

    record Ratio(int passed, int total, List<Line> failed) {

        double value() {
            return total == 0 ? 1.0 : (double) passed / total;
        }

        String text() {
            return "%.1f%% (%d / %d)".formatted(value() * 100, passed, total);
        }
    }

    private static Ratio ratio(List<Line> lines, Predicate<Line> target, Predicate<Line> pass) {
        List<Line> targets = lines.stream().filter(target).toList();
        List<Line> failed = targets.stream().filter(pass.negate()).toList();
        return new Ratio(targets.size() - failed.size(), targets.size(), failed);
    }

    private static double median(List<Double> sorted) {
        return percentile(sorted, 0.5);
    }

    private static double percentile(List<Double> sorted, double p) {
        return sorted.get((int) Math.ceil(p * sorted.size()) - 1);
    }

    private void writeReport(int caseCount, int lineCount, int sweepCount, Ratio bestMatch, Ratio fullMatch, Ratio sweepBest,
                             Ratio sweepFull, Ratio exclusion, List<Double> overbuys, double overbuyTwiceShare,
                             List<Double> excess, double excessShare, List<Double> premiums, int budgetCases, int withinAtFirst, int withinAfterOptions,
                             int budgetMissed, int deterministic, Baseline baseline, List<String> caseRows) {
        List<String> out = new ArrayList<>();
        out.add("# 견적 품질 평가");
        out.add("");
        out.add("운영 시드 카탈로그(상품 %d개)에 메뉴 계획 %d개(재료 %d줄)와 조건 조합 전수 %d개를 넣은 결과."
                .formatted(CATALOG.products().size(), caseCount, lineCount, sweepCount));
        out.add("정답은 카탈로그에서 계산한 \"고를 수 있었던 최선\"이다.");
        out.add("");
        out.add("| 지표 | 결과 | 기준 |");
        out.add("|---|---|---|");
        out.add("| 조건을 가장 많이 맞춘 상품을 고름 | %s | 100%% |".formatted(bestMatch.text()));
        out.add("| 조건을 전부 맞출 수 있을 때 전부 맞춤 | %s | 참고 |".formatted(fullMatch.text()));
        out.add("| 조건 조합 전수: 가장 많이 맞춤 | %s | 100%% |".formatted(sweepBest.text()));
        out.add("| 조건 조합 전수: 전부 맞춤 | %s | 참고 |".formatted(sweepFull.text()));
        out.add("| 제외 여부·사유가 카탈로그와 일치 | %s | 100%% |".formatted(exclusion.text()));
        out.add("| 같은 입력이면 같은 견적 | %d / %d | 전부 |".formatted(deterministic, caseCount));
        out.add("| 필요량 대비 구매량 (중앙값 / p90) | %.2f배 / %.2f배 | 참고 (팩 크기 때문에 생기는 초과 포함) |"
                .formatted(median(overbuys), percentile(overbuys, 0.9)));
        out.add("| 필요량의 2배 넘게 사는 줄 | %.1f%% | 참고 |".formatted(overbuyTwiceShare * 100));
        out.add("| 더 적게 살 수 있었는데 더 많이 산 줄 | %.1f%% | %.1f%% 이하 |"
                .formatted(excessShare * 100, baseline.excessShare() * 100));
        out.add("| 살 수 있었던 최소량 대비 구매량 (중앙값 / p90) | %.2f배 / %.2f배 | p90 %.2f배 이하 |"
                .formatted(median(excess), percentile(excess, 0.9), baseline.excessP90()));
        out.add("| 같은 조건 수 최저가 대비 고른 상품 가격 (중앙값 / p90) | %.2f배 / %.2f배 | 참고 |"
                .formatted(median(premiums), percentile(premiums, 0.9)));
        out.add("| 예산 견적: 처음부터 예산 안 | %d / %d | 참고 |".formatted(withinAtFirst, budgetCases));
        out.add("| 예산 견적: 예산 후보를 누르면 예산 안 | %d / %d | 참고 |".formatted(withinAfterOptions, budgetCases));
        out.add("| 예산 견적: 맞출 수 있었는데 못 맞춤 | %d | 0 |".formatted(budgetMissed));
        out.add("");
        List<Line> failed = new ArrayList<>();
        failed.addAll(bestMatch.failed());
        failed.addAll(sweepBest.failed());
        failed.addAll(exclusion.failed());
        if (!failed.isEmpty()) {
            out.add("## ❌ 기준을 못 지킨 줄");
            out.add("");
            failed.forEach(line -> out.add("- %s %s 조건=%s 고른 상품=%s 맞춘 조건=%s 제외=%s (카탈로그 기준 제외=%s)".formatted(
                    line.caseId(), line.ingredient(), line.conditions(),
                    line.picked() == null ? "-" : line.picked().getName(),
                    line.picked() == null ? "-" : matched(line), line.actualExclusion(), expectedExclusion(line))));
            out.add("");
        }
        out.add("## 케이스별 결과");
        out.add("");
        out.add("| ID | 메뉴 | 조건 | 담은 재료 | 제외 | 합계 | 예산 (처음 → 후보 적용) |");
        out.add("|---|---|---|---|---|---|---|");
        out.addAll(caseRows);
        String report = String.join("\n", out) + "\n";
        try {
            Files.createDirectories(REPORT.getParent());
            Files.writeString(REPORT, report, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        System.out.println(report);
    }
}
