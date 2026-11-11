package com.server.zelo.ai.eval;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.Collectors;

// 평가 결과를 묶음별 표로 만들고 마크다운 파일로 남긴다
final class AiEvalReport {

    // gpt-6-luna 가격(1M 토큰당 달러, 2026-10-20 OpenAI 가격표). 출력 토큰에는 추론 토큰이 들어간다.
    // 캐시된 입력 토큰 할인은 빼고 계산해서 실제 요금보다 조금 크게 나온다
    static final double INPUT_USD_PER_MILLION = 0.10;
    static final double OUTPUT_USD_PER_MILLION = 0.50;
    private static final int WRONG_FIELD_TOP = 3;
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm");
    private static final DateTimeFormatter TITLE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    enum Group {
        SINGLE("단일 문장", "의도, 메뉴, 인원, 예산, 조건 칸"),
        MULTI_TURN("여러 턴", "앞 대화가 있을 때 의도와 앞 조건 유지"),
        EDIT("견적 수정", "빼기, 수량 변경, 추가, 되묻기"),
        INJECTION("프롬프트 주입", "지시를 따르지 않고 나머지 말대로 정리");

        private final String title;
        private final String meaning;

        Group(String title, String meaning) {
            this.title = title;
            this.meaning = meaning;
        }
    }

    enum Kind {
        MENU_PLAN, QUOTE_EDIT
    }

    // 호출 한 번의 결과. error가 있으면 답을 받지 못했고, invalidField가 있으면 받았지만 서버 검증에 걸렸다(앱은 재시도)
    record Result(
            String caseId,
            Group group,
            Kind kind,
            String text,
            int round,
            long latencyMs,
            String error,
            String invalidField,
            List<String> mismatches,
            String answer,
            String model,
            Integer promptTokens,
            Integer completionTokens
    ) {

        boolean answered() {
            return error == null;
        }

        boolean valid() {
            return answered() && invalidField == null;
        }

        // 첫 답이 검증을 통과하고 정답과 같을 때만 맞힌 것으로 센다
        boolean correct() {
            return valid() && mismatches.isEmpty();
        }

        double costUsd() {
            if (promptTokens == null || completionTokens == null) {
                return 0;
            }
            return (promptTokens * INPUT_USD_PER_MILLION + completionTokens * OUTPUT_USD_PER_MILLION) / 1_000_000;
        }
    }

    private final List<Result> results;
    private final int rounds;
    private final LocalDateTime measuredAt = LocalDateTime.now();

    AiEvalReport(List<Result> results, int rounds) {
        this.results = results;
        this.rounds = rounds;
    }

    String render() {
        StringBuilder out = new StringBuilder();
        long caseCount = results.stream().map(Result::caseId).distinct().count();
        String models = results.stream().map(Result::model).filter(Objects::nonNull).distinct().collect(Collectors.joining(", "));
        out.append("# AI 평가 (").append(TITLE_TIME.format(measuredAt)).append(")\n\n")
                .append("- 문장 ").append(caseCount).append("개 × ").append(rounds).append("회 = 호출 ")
                .append(results.size()).append("회, 모델 ").append(models.isEmpty() ? "-" : models).append('\n')
                .append("- 재시도 없이 첫 답만 채점한다. 검증에 걸린 답(앱은 1회 재시도)도 오답으로 센다\n")
                .append("- 비용은 입력 $").append(INPUT_USD_PER_MILLION).append(", 출력 $").append(OUTPUT_USD_PER_MILLION)
                .append(" / 1M 토큰(2026-10-20 가격표, 캐시 할인 제외)으로 계산했다\n")
                .append("- 주입은 LLM이 지시를 따랐는지만 본다. 결제·주문은 서버가 직접 불러야만 실행되어 LLM이 실행할 수 없다\n\n");
        appendSummary(out);
        appendWrongFields(out);
        appendCaseTable(out);
        appendWrongAnswers(out);
        return out.toString();
    }

    Path write(Path dir) {
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve("ai-eval-" + FILE_TIME.format(measuredAt) + ".md");
            Files.writeString(file, render(), StandardCharsets.UTF_8);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void appendSummary(StringBuilder out) {
        out.append("## 묶음별 결과\n\n")
                .append("| 묶음 | 보는 것 | 문장 | 호출 | 정답률 | 검증 실패 | 호출 실패 | p50 | p95 | 최대 | 평균 입력 토큰 | 평균 출력 토큰 | 건당 비용 | 1,000건 비용 |\n")
                .append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (Group group : Group.values()) {
            List<Result> grouped = results.stream().filter(result -> result.group() == group).toList();
            if (!grouped.isEmpty()) {
                appendSummaryRow(out, group.title, group.meaning, Stats.of(grouped));
            }
        }
        appendSummaryRow(out, "**전체**", "", Stats.of(results));
        out.append('\n');
    }

    private static void appendSummaryRow(StringBuilder out, String title, String meaning, Stats stats) {
        out.append("| ").append(title).append(" | ").append(meaning)
                .append(" | ").append(stats.cases).append(" | ").append(stats.calls)
                .append(" | ").append(percent(stats.accuracy)).append(" | ").append(percent(stats.invalidRate))
                .append(" | ").append(percent(stats.errorRate))
                .append(" | ").append(seconds(stats.p50)).append(" | ").append(seconds(stats.p95))
                .append(" | ").append(seconds(stats.max))
                .append(" | ").append(stats.avgPromptTokens).append(" | ").append(stats.avgCompletionTokens)
                .append(" | $").append(String.format("%.6f", stats.avgCostUsd))
                .append(" | $").append(String.format("%.3f", stats.avgCostUsd * 1_000)).append(" |\n");
    }

    // 오답에서 어느 칸을 많이 틀렸는지. 검증 실패와 호출 실패도 따로 센다
    private void appendWrongFields(StringBuilder out) {
        out.append("## 많이 틀린 칸 (묶음별 상위 ").append(WRONG_FIELD_TOP).append("개)\n\n");
        for (Group group : Group.values()) {
            Map<String, Long> counts = results.stream()
                    .filter(result -> result.group() == group && !result.correct())
                    .flatMap(result -> wrongFields(result).stream())
                    .collect(Collectors.groupingBy(field -> field, LinkedHashMap::new, Collectors.counting()));
            if (results.stream().noneMatch(result -> result.group() == group)) {
                continue;
            }
            String top = counts.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .limit(WRONG_FIELD_TOP)
                    .map(entry -> entry.getKey() + " " + entry.getValue() + "회")
                    .collect(Collectors.joining(", "));
            out.append("- ").append(group.title).append(": ").append(top.isEmpty() ? "없음" : top).append('\n');
        }
        out.append('\n');
    }

    private static List<String> wrongFields(Result result) {
        if (!result.answered()) {
            return List.of("호출 실패(" + result.error() + ")");
        }
        if (!result.valid()) {
            return List.of("검증 실패(" + result.invalidField() + ")");
        }
        return result.mismatches().stream().map(mismatch -> mismatch.substring(0, mismatch.indexOf(':'))).distinct().toList();
    }

    // 한 번이라도 틀린 문장만 적는다. 칸은 "맞힌 횟수/부른 횟수"
    private void appendCaseTable(StringBuilder out) {
        Map<String, List<Result>> byCase = results.stream()
                .collect(Collectors.groupingBy(Result::caseId, LinkedHashMap::new, Collectors.toList()));
        List<Map.Entry<String, List<Result>>> wrong = byCase.entrySet().stream()
                .filter(entry -> !entry.getValue().stream().allMatch(Result::correct))
                .toList();
        out.append("## 틀린 적 있는 문장 (").append(wrong.size()).append("개, 모두 맞힌 문장 ")
                .append(byCase.size() - wrong.size()).append("개)\n\n")
                .append("| 묶음 | 문장 id | 맞힌 수 | 문장 |\n|---|---|---|---|\n");
        for (Map.Entry<String, List<Result>> entry : wrong) {
            Result first = entry.getValue().getFirst();
            long correct = entry.getValue().stream().filter(Result::correct).count();
            out.append("| ").append(first.group().title).append(" | ").append(entry.getKey())
                    .append(" | ").append(correct).append('/').append(entry.getValue().size())
                    .append(" | ").append(cell(first.text())).append(" |\n");
        }
        out.append('\n');
    }

    private void appendWrongAnswers(StringBuilder out) {
        out.append("## 틀린 답\n\n| 문장 id | 회차 | 틀린 내용 | LLM 답 |\n|---|---|---|---|\n");
        results.stream()
                .filter(Predicate.not(Result::correct))
                .sorted(Comparator.comparing(Result::caseId).thenComparing(Result::round))
                .forEach(result -> out.append("| ").append(result.caseId()).append(" | ").append(result.round())
                        .append(" | ").append(cell(String.join("; ", reasons(result))))
                        .append(" | ").append(cell(Objects.toString(result.answer(), "-"))).append(" |\n"));
        out.append('\n');
    }

    static List<String> reasons(Result result) {
        if (!result.answered()) {
            return List.of("호출 실패 " + result.error());
        }
        List<String> reasons = new ArrayList<>();
        if (!result.valid()) {
            reasons.add("검증 실패 " + result.invalidField());
        }
        reasons.addAll(result.mismatches());
        return reasons;
    }

    private static String cell(String text) {
        return text.replace("|", "/").replace("\n", " ");
    }

    private static String percent(double ratio) {
        return String.format("%.1f%%", ratio * 100);
    }

    private static String seconds(long millis) {
        return String.format("%.1fs", millis / 1000.0);
    }

    // 묶음 하나의 숫자. 응답 시간은 실패한 호출까지 넣는다(사용자가 기다린 시간이므로)
    record Stats(long cases, int calls, double accuracy, double invalidRate, double errorRate, long p50, long p95, long max,
                 long avgPromptTokens, long avgCompletionTokens, double avgCostUsd) {

        static Stats of(List<Result> results) {
            int calls = results.size();
            List<Long> latencies = results.stream().map(Result::latencyMs).sorted().toList();
            List<Result> answered = results.stream().filter(Result::answered).toList();
            return new Stats(
                    results.stream().map(Result::caseId).distinct().count(),
                    calls,
                    ratio(results.stream().filter(Result::correct).count(), calls),
                    ratio(answered.stream().filter(Predicate.not(Result::valid)).count(), calls),
                    ratio(calls - answered.size(), calls),
                    percentile(latencies, 50),
                    percentile(latencies, 95),
                    latencies.isEmpty() ? 0 : latencies.getLast(),
                    Math.round(answered.stream().mapToInt(result -> Objects.requireNonNullElse(result.promptTokens(), 0))
                            .average().orElse(0)),
                    Math.round(answered.stream().mapToInt(result -> Objects.requireNonNullElse(result.completionTokens(), 0))
                            .average().orElse(0)),
                    answered.stream().mapToDouble(Result::costUsd).average().orElse(0));
        }

        // 가장 가까운 순위 방식: 정렬한 값에서 전체의 p% 위치(올림)에 있는 값
        static long percentile(List<Long> sorted, int percent) {
            if (sorted.isEmpty()) {
                return 0;
            }
            int rank = (int) Math.ceil(percent / 100.0 * sorted.size());
            return sorted.get(Math.max(rank, 1) - 1);
        }

        private static double ratio(long count, int total) {
            return total == 0 ? 0 : (double) count / total;
        }
    }
}
