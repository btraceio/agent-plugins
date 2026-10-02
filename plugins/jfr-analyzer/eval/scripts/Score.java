///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS com.fasterxml.jackson.core:jackson-databind:2.18.2
//JAVA 17

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * jfr-analyzer eval scorer.
 *
 * <pre>
 * Usage:
 *     jbang Score.java [--scenario &lt;id&gt;] [--multi-judge] [--eval-dir &lt;dir&gt;]
 *
 * Reads:  eval/results/&lt;id&gt;/run-*&#47;focus.json  and  .../breakpoint.txt
 * Reads:  eval/corpus/manifest.json
 * Writes: eval/results/report.md
 *
 * Prerequisites:
 *     ANTHROPIC_API_KEY  — required for semantic scoring
 *     OPENAI_API_KEY     — optional; enables multi-judge mode
 * </pre>
 *
 * <p>The eval directory is {@code --eval-dir}, else found from the working directory: run this from
 * {@code eval/} or {@code eval/scripts/}.
 */
public class Score {

    static final ObjectMapper JSON = new ObjectMapper();
    static final HttpClient HTTP = HttpClient.newHttpClient();

    // ── Data loading ──────────────────────────────────────────────────────────

    record Run(int run, JsonNode focus, String breakpointText) {}

    static List<Run> loadRunOutputs(Path resultsDir, String scenarioId, int nRuns) throws IOException {
        List<Run> runs = new ArrayList<>();
        for (int k = 1; k <= nRuns; k++) {
            Path runDir = resultsDir.resolve(scenarioId).resolve("run-" + k);
            Path focusPath = runDir.resolve("focus.json");
            Path bpPath = runDir.resolve("breakpoint.txt");
            if (!Files.exists(focusPath)) {
                continue;
            }
            runs.add(new Run(k, JSON.readTree(focusPath.toFile()), Files.exists(bpPath) ? Files.readString(bpPath) : ""));
        }
        return runs;
    }

    // ── Score results ─────────────────────────────────────────────────────────

    record StructuralRun(int run, double recall, double precision, double startHereAcc, double absentPenalty,
                         Double corrRecall, boolean passed) {}

    record Verdict(String provider, JsonNode body, String error, double score) {}

    record SemanticRun(int run, double meanScore, List<Verdict> verdicts, boolean disagreement, boolean passed) {}

    /** Outcome of scoring one scenario; only the fields for its tier are populated. */
    static class Outcome {
        String tier;
        int nRuns;
        int nPassed;
        boolean passAt1;
        double passAtK;
        boolean overallPass;
        // structural
        List<StructuralRun> structuralRuns = List.of();
        double meanRecall;
        double meanPrecision;
        double[] ciRecall95;
        boolean startMajority;
        // semantic
        List<SemanticRun> semanticRuns = List.of();
        double meanScore;
        boolean needsReview;
    }

    record Result(String id, int group, Outcome score) {}

    // ── Structural scoring ────────────────────────────────────────────────────

    static Set<String> ids(JsonNode areas, boolean onlyStartHere) {
        Set<String> out = new LinkedHashSet<>();
        for (JsonNode a : areas) {
            if (!onlyStartHere || a.path("startHere").asBoolean(false)) {
                out.add(a.get("id").asText());
            }
        }
        return out;
    }

    static Set<String> kinds(JsonNode correlations) {
        Set<String> out = new LinkedHashSet<>();
        for (JsonNode c : correlations) {
            out.add(c.get("kind").asText());
        }
        return out;
    }

    static int overlap(Set<String> a, Set<String> b) {
        Set<String> i = new LinkedHashSet<>(a);
        i.retainAll(b);
        return i.size();
    }

    static Outcome scoreStructural(JsonNode scenario, List<Run> runs) {
        JsonNode expected = scenario.get("expected");
        Set<String> expAreas = ids(expected.path("focusAreas"), false);
        Set<String> expAbsent = new LinkedHashSet<>();
        expected.path("absent_area_ids").forEach(n -> expAbsent.add(n.asText()));
        Set<String> expCorrKinds = kinds(expected.path("crossAreaCorrelations"));
        Set<String> expStartHere = ids(expected.path("focusAreas"), true);

        double passThresholdRecall = 0.9;
        double passThresholdPrecision = 0.8;

        List<StructuralRun> perRun = new ArrayList<>();
        for (Run run : runs) {
            Set<String> detAreas = ids(run.focus().path("focusAreas"), false);
            Set<String> detKinds = kinds(run.focus().path("crossAreaCorrelations"));
            Set<String> detStart = ids(run.focus().path("focusAreas"), true);

            double recall = !expAreas.isEmpty() ? (double) overlap(expAreas, detAreas) / expAreas.size()
                : (detAreas.isEmpty() ? 1.0 : 0.0);
            double precision = !detAreas.isEmpty() ? (double) overlap(expAreas, detAreas) / detAreas.size()
                : (expAreas.isEmpty() ? 1.0 : 0.0);
            double startHereAcc = !expStartHere.isEmpty() ? (double) overlap(expStartHere, detStart) / expStartHere.size()
                : (detStart.isEmpty() ? 1.0 : 0.0);
            double absentPenalty = -0.5 * expAbsent.stream().filter(detAreas::contains).count();
            Double corrRecall = !expCorrKinds.isEmpty() ? (double) overlap(expCorrKinds, detKinds) / expCorrKinds.size() : null;

            boolean passed = recall >= passThresholdRecall
                && precision >= passThresholdPrecision
                && (expStartHere.isEmpty() || startHereAcc >= 0.5);

            perRun.add(new StructuralRun(run.run(), round3(recall), round3(precision), round3(startHereAcc),
                absentPenalty, corrRecall != null ? round3(corrRecall) : null, passed));
        }

        int nTotal = perRun.size();
        int nPassed = (int) perRun.stream().filter(StructuralRun::passed).count();
        boolean passAt1 = !perRun.isEmpty() && perRun.get(0).passed();
        double passAtK = nTotal > 1 ? passAtK(nTotal, nPassed, nTotal) : (passAt1 ? 1.0 : 0.0);

        List<Double> recalls = perRun.stream().map(StructuralRun::recall).toList();
        boolean startMajority = nTotal > 0
            && perRun.stream().filter(r -> r.startHereAcc() >= 0.5).count() > nTotal / 2.0;

        Outcome s = new Outcome();
        s.tier = "structural";
        s.structuralRuns = perRun;
        s.nRuns = nTotal;
        s.nPassed = nPassed;
        s.passAt1 = passAt1;
        s.passAtK = round3(passAtK);
        s.meanRecall = round3(mean(recalls));
        s.meanPrecision = round3(mean(perRun.stream().map(StructuralRun::precision).toList()));
        double[] ci = ci95(recalls);
        s.ciRecall95 = new double[] {round3(ci[0]), round3(ci[1])};
        s.startMajority = startMajority;
        s.overallPass = nPassed >= Math.ceil(nTotal * 0.6) && startMajority;
        return s;
    }

    // ── Semantic scoring ──────────────────────────────────────────────────────

    static final String JUDGE_PROMPT_TMPL = """
        SCENARIO: %s
        JUDGE QUESTION: %s
        REQUIRED EVIDENCE: %s
        MUST NOT APPEAR: %s

        SKILL OUTPUT:
        --- focus.json ---
        %s
        --- breakpoint text ---
        %s
        ---

        RUBRIC:
        0.0 — missed the problem or wrong root cause
        0.5 — right area, wrong mechanism or severity
        1.0 — correct problem, root cause, and severity

        Step 1: reason through what the skill found (2-3 sentences)
        Step 2: list which required_evidence_keywords appear
        Step 3: list any must_not_hallucinate items that appeared
        Step 4: output ONLY JSON: {"reasoning":"...","evidence_found":[...],"hallucinations":[...],"score":0.0}
        """;

    static JsonNode post(String url, String bodyJson, String... headers) throws IOException, InterruptedException {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(120))
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(bodyJson));
        for (int i = 0; i < headers.length; i += 2) {
            req.header(headers[i], headers[i + 1]);
        }
        HttpResponse<String> resp = HTTP.send(req.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("HTTP " + resp.statusCode() + " from " + url + ": " + resp.body());
        }
        return JSON.readTree(resp.body());
    }

    /** Extracts the last {...} span from a model reply and parses it as the verdict. */
    static JsonNode extractVerdict(String text) throws IOException {
        int start = text.lastIndexOf('{');
        int end = text.lastIndexOf('}') + 1;
        if (start == -1 || end <= start) {
            throw new IOException("No JSON in judge response: " + text);
        }
        JsonNode v = JSON.readTree(text.substring(start, end));
        if (!v.has("score")) {
            throw new IOException("Judge response has no score: " + text);
        }
        return v;
    }

    static JsonNode callClaude(String prompt) throws IOException, InterruptedException {
        ObjectNode body = JSON.createObjectNode().put("model", "claude-opus-4-8").put("max_tokens", 1024);
        body.putArray("messages").addObject().put("role", "user").put("content", prompt);
        JsonNode resp = post("https://api.anthropic.com/v1/messages", JSON.writeValueAsString(body),
            "x-api-key", System.getenv("ANTHROPIC_API_KEY"), "anthropic-version", "2023-06-01");
        return extractVerdict(resp.at("/content/0/text").asText());
    }

    static JsonNode callGpt4o(String prompt) throws IOException, InterruptedException {
        ObjectNode body = JSON.createObjectNode().put("model", "gpt-4o").put("max_tokens", 1024);
        body.putArray("messages").addObject().put("role", "user").put("content", prompt);
        JsonNode resp = post("https://api.openai.com/v1/chat/completions", JSON.writeValueAsString(body),
            "Authorization", "Bearer " + System.getenv("OPENAI_API_KEY"));
        return extractVerdict(resp.at("/choices/0/message/content").asText());
    }

    interface Judge {
        JsonNode call(String prompt) throws IOException, InterruptedException;
    }

    static Verdict judge(String provider, Judge judge, String prompt) {
        try {
            JsonNode v = judge.call(prompt);
            return new Verdict(provider, v, null, v.get("score").asDouble());
        } catch (IOException | RuntimeException e) {
            return new Verdict(provider, null, String.valueOf(e.getMessage()), 0.0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Verdict(provider, null, "interrupted", 0.0);
        }
    }

    static String join(JsonNode array) {
        List<String> parts = new ArrayList<>();
        array.forEach(n -> parts.add(n.asText()));
        return String.join(", ", parts);
    }

    static Outcome scoreSemantic(JsonNode scenario, List<Run> runs, boolean multiJudge) throws IOException {
        JsonNode rubric = scenario.get("expected").get("rubric");
        String description = scenario.get("description").asText();
        double passThreshold = 0.7;
        boolean useOpenAi = multiJudge && System.getenv("OPENAI_API_KEY") != null;
        List<SemanticRun> perRun = new ArrayList<>();

        for (Run run : runs) {
            String prompt = JUDGE_PROMPT_TMPL.formatted(
                description,
                rubric.get("judge_question").asText(),
                join(rubric.get("required_evidence_keywords")),
                join(rubric.get("must_not_hallucinate")),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(run.focus()),
                run.breakpointText());

            List<Verdict> verdicts = new ArrayList<>();
            verdicts.add(judge("claude", Score::callClaude, prompt));
            if (useOpenAi) {
                verdicts.add(judge("gpt-4o", Score::callGpt4o, prompt));
            }
            List<Double> scores = verdicts.stream().map(Verdict::score).toList();

            double meanScore = mean(scores);
            boolean disagreement = scores.size() > 1 && Math.abs(scores.get(0) - scores.get(scores.size() - 1)) > 0.5;
            boolean passed = meanScore >= passThreshold && !disagreement;
            perRun.add(new SemanticRun(run.run(), round3(meanScore), verdicts, disagreement, passed));
        }

        int nTotal = perRun.size();
        int nPassed = (int) perRun.stream().filter(SemanticRun::passed).count();
        boolean passAt1 = !perRun.isEmpty() && perRun.get(0).passed();
        double passAtK = nTotal > 1 ? passAtK(nTotal, nPassed, nTotal) : (passAt1 ? 1.0 : 0.0);
        boolean needsReview = perRun.stream().anyMatch(SemanticRun::disagreement);

        Outcome s = new Outcome();
        s.tier = "semantic";
        s.semanticRuns = perRun;
        s.nRuns = nTotal;
        s.nPassed = nPassed;
        s.passAt1 = passAt1;
        s.passAtK = round3(passAtK);
        s.meanScore = round3(mean(perRun.stream().map(SemanticRun::meanScore).toList()));
        s.needsReview = needsReview;
        s.overallPass = nPassed >= Math.ceil(nTotal * 0.6) && !needsReview;
        return s;
    }

    // ── Statistics helpers ────────────────────────────────────────────────────

    static double round3(double v) {
        return Math.rint(v * 1000.0) / 1000.0;
    }

    static double mean(List<Double> values) {
        return values.isEmpty() ? 0.0 : values.stream().mapToDouble(Double::doubleValue).sum() / values.size();
    }

    /** Two-sided 95% Student-t critical values for df = 1..30. */
    static final double[] T_975 = {
        12.706, 4.303, 3.182, 2.776, 2.571, 2.447, 2.365, 2.306, 2.262, 2.228,
        2.201, 2.179, 2.160, 2.145, 2.131, 2.120, 2.110, 2.101, 2.093, 2.086,
        2.080, 2.074, 2.069, 2.064, 2.060, 2.056, 2.052, 2.048, 2.045, 2.042};

    static double tCritical(int df) {
        // Beyond the table, 1.96 + 2.4/df tracks the exact value to ~0.003 (df=40: 2.020, df=120: 1.980).
        return df <= T_975.length ? T_975[df - 1] : 1.96 + 2.4 / df;
    }

    /** 95% confidence interval of the mean (Student-t), clamped to [0, 1]. */
    static double[] ci95(List<Double> values) {
        if (values.size() < 2) {
            double v = values.isEmpty() ? 0.0 : values.get(0);
            return new double[] {v, v};
        }
        int n = values.size();
        double m = mean(values);
        double variance = values.stream().mapToDouble(v -> (v - m) * (v - m)).sum() / (n - 1);
        double half = tCritical(n - 1) * Math.sqrt(variance / n);
        return new double[] {Math.max(0.0, m - half), Math.min(1.0, m + half)};
    }

    /** pass@k = 1 - C(n-c, k) / C(n, k) */
    static double passAtK(int n, int c, int k) {
        if (n - c < k) {
            return 1.0;
        }
        return 1.0 - comb(n - c, k) / comb(n, k);
    }

    static double comb(int n, int k) {
        double result = 1.0;
        for (int i = 1; i <= k; i++) {
            result = result * (n - k + i) / i;
        }
        return result;
    }

    // ── Report rendering ──────────────────────────────────────────────────────

    static String f1(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    static String f2(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    static String renderReport(List<Result> results, String date) {
        List<Result> structural = results.stream().filter(r -> r.score().tier.equals("structural")).toList();
        List<Result> semantic = results.stream().filter(r -> r.score().tier.equals("semantic")).toList();

        List<String> lines = new ArrayList<>();
        lines.add("# jfr-analyzer Eval Results — " + date);
        lines.add("");

        if (!structural.isEmpty()) {
            lines.add("## Structural (Groups 1 & 2) — " + structural.get(0).score().nRuns + " runs each");
            lines.add("");
            lines.add("| Scenario | Recall p@1 | p@k | Precision p@1 | startHere | Corr. | Result |");
            lines.add("|----------|-----------|-----|---------------|-----------|-------|--------|");
            for (Result r : structural) {
                Outcome s = r.score();
                StructuralRun pr = s.structuralRuns.isEmpty() ? null : s.structuralRuns.get(0);
                Double corr = pr == null ? null : pr.corrRecall();
                long shVotes = s.structuralRuns.stream().filter(x -> x.startHereAcc() >= 0.5).count();
                lines.add(String.format("| %-28s | %s | %s | %s | %s %d/%d | %s | %s |",
                    r.id(),
                    f1(pr == null ? 0 : pr.recall()),
                    f1(s.passAtK),
                    f1(pr == null ? 0 : pr.precision()),
                    s.startMajority ? "✓" : "✗", shVotes, s.nRuns,
                    corr != null ? f1(corr) : "N/A",
                    s.overallPass ? "**PASS**" : "FAIL"));
            }
            lines.add("");
        }

        if (!semantic.isEmpty()) {
            lines.add("## Semantic (Groups 3 & 4) — " + semantic.get(0).score().nRuns + " runs each");
            lines.add("");
            lines.add("| Scenario | Score p@1 | p@k | Agreement | Result |");
            lines.add("|----------|-----------|-----|-----------|--------|");
            for (Result r : semantic) {
                Outcome s = r.score();
                SemanticRun pr = s.semanticRuns.isEmpty() ? null : s.semanticRuns.get(0);
                lines.add(String.format("| %-28s | %s | %s | %s | %s |",
                    r.id(),
                    f1(pr == null ? 0 : pr.meanScore()),
                    f1(s.passAtK),
                    s.needsReview ? "⚠️" : "✓",
                    s.overallPass ? "**PASS**" : (s.needsReview ? "⚠️ needs-human-review" : "FAIL")));
            }
            lines.add("");
        }

        lines.add("## Summary");
        if (!structural.isEmpty()) {
            long pass = structural.stream().filter(r -> r.score().overallPass).count();
            lines.add("Structural: " + pass + "/" + structural.size() + " PASS"
                + "  ·  mean recall " + f2(mean(structural.stream().map(r -> r.score().meanRecall).toList()))
                + "  ·  mean precision " + f2(mean(structural.stream().map(r -> r.score().meanPrecision).toList())));
        }
        if (!semantic.isEmpty()) {
            long pass = semantic.stream().filter(r -> r.score().overallPass).count();
            lines.add("Semantic:   " + pass + "/" + semantic.size() + " PASS"
                + "  ·  mean judge score " + f2(mean(semantic.stream().map(r -> r.score().meanScore).toList())));
        }
        return lines.stream().collect(Collectors.joining("\n")) + "\n";
    }

    // ── CLI ───────────────────────────────────────────────────────────────────

    /** The eval directory holds corpus/manifest.json; accept it, its scripts/ subdir, or its parent. */
    static Path findEvalDir(String explicit) {
        List<Path> candidates = new ArrayList<>();
        if (explicit != null) {
            candidates.add(Path.of(explicit).toAbsolutePath());
        } else {
            Path cwd = Path.of("").toAbsolutePath();
            candidates.add(cwd);
            candidates.add(cwd.getParent());
            candidates.add(cwd.resolve("eval"));
        }
        for (Path c : candidates) {
            if (c != null && Files.exists(c.resolve("corpus").resolve("manifest.json"))) {
                return c;
            }
        }
        throw new IllegalStateException("eval directory (with corpus/manifest.json) not found; "
            + "run from eval/ or eval/scripts/, or pass --eval-dir");
    }

    public static void main(String[] argv) throws Exception {
        String scenarioFilter = null;
        String evalDirArg = null;
        boolean multiJudge = false;
        for (int i = 0; i < argv.length; i++) {
            switch (argv[i]) {
                case "--scenario" -> scenarioFilter = argv[++i];
                case "--eval-dir" -> evalDirArg = argv[++i];
                case "--multi-judge" -> multiJudge = true;
                default -> {
                    System.err.println("usage: Score.java [--scenario <id>] [--multi-judge] [--eval-dir <dir>]");
                    System.exit(2);
                }
            }
        }

        if (System.getenv("ANTHROPIC_API_KEY") == null) {
            System.out.println("ERROR: ANTHROPIC_API_KEY not set (required for semantic scoring)");
            System.exit(1);
        }

        Path evalDir = findEvalDir(evalDirArg);
        Path resultsDir = evalDir.resolve("results");
        JsonNode manifest = JSON.readTree(evalDir.resolve("corpus").resolve("manifest.json").toFile());
        List<JsonNode> scenarios = new ArrayList<>();
        manifest.get("scenarios").forEach(scenarios::add);
        if (scenarioFilter != null) {
            String wanted = scenarioFilter;
            scenarios = scenarios.stream().filter(s -> s.get("id").asText().equals(wanted)).toList();
            if (scenarios.isEmpty()) {
                System.out.println("ERROR: scenario '" + wanted + "' not found in manifest");
                System.exit(1);
            }
        }

        List<Result> results = new ArrayList<>();
        for (JsonNode scenario : scenarios) {
            String sid = scenario.get("id").asText();
            String tier = scenario.get("expected").get("scoring_tier").asText();
            int nRuns = scenario.path("eval_runs").asInt(5);

            List<Run> runs = loadRunOutputs(resultsDir, sid, nRuns);
            if (runs.isEmpty()) {
                System.out.println("  [" + sid + "] No run outputs found — skipping");
                continue;
            }

            System.out.println("  [" + sid + "] scoring " + runs.size() + " runs (" + tier + ")...");
            Outcome score = tier.equals("structural") ? scoreStructural(scenario, runs) : scoreSemantic(scenario, runs, multiJudge);

            results.add(new Result(sid, scenario.get("group").asInt(), score));
            System.out.println("    → " + (score.overallPass ? "PASS" : "FAIL"));
        }

        String report = renderReport(results, LocalDate.now(ZoneOffset.UTC).toString());
        Path reportPath = resultsDir.resolve("report.md");
        Files.createDirectories(resultsDir);
        Files.writeString(reportPath, report);
        System.out.println("\nReport written to " + reportPath);
        System.out.println(report);
    }
}
