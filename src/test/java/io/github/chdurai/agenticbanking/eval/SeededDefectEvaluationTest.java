package io.github.chdurai.agenticbanking.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chdurai.agenticbanking.flow.protocol.ContradictionDetector;
import io.github.chdurai.agenticbanking.flow.state.Finding;
import io.github.chdurai.agenticbanking.flow.state.Provenance;
import io.github.chdurai.agenticbanking.model.AgentReport;
import io.github.chdurai.agenticbanking.model.LoanApplication;
import io.github.chdurai.agenticbanking.model.LoanDecision;
import io.github.chdurai.agenticbanking.policy.InvariantChecker;
import io.github.chdurai.agenticbanking.policy.LoanPolicyEngine;
import io.github.chdurai.agenticbanking.policy.LoanPolicyProperties;
import io.github.chdurai.agenticbanking.policy.PolicyMerge;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Paired clean/seeded drafts exercise real pure code, never production critic bypasses. */
class SeededDefectEvaluationTest {
    private static final Instant TIME = Instant.parse("2026-06-17T12:00:00Z");
    private final LoanPolicyEngine policy = new LoanPolicyEngine(LoanPolicyProperties.defaults());
    private final InvariantChecker invariants = new InvariantChecker(LoanPolicyProperties.defaults());
    private final ContradictionDetector detector = new ContradictionDetector();
    private final PolicyMerge merge = new PolicyMerge();

    record Observation(String id, String defect, String expectedRule, List<String> invariantRules,
                       List<String> detectorRules, boolean caught, boolean postMergeIssuesEmpty,
                       List<String> remainingInvariantRules, List<String> remainingDetectorRules) {}
    record Metrics(int cases, long caught, double catchRate, long postMergeClean, double postMergeCleanRate) {}
    record Report(String schemaVersion, String corpusVersion, String mode, int pairedCleanCases,
                  long cleanFalsePositives, double cleanFalsePositiveRate, Metrics overall,
                  Map<String, Metrics> byDefect, Double independentCriticCatchRate,
                  Double productionCriticCallsPerCase, boolean syntheticSeventyPercentRepairTargetMet,
                  List<Observation> observations, List<String> limitations) {}

    @Test void pairedSeededDefectsPublishExactCatchAndRepairRatesWithoutRemovingTheCritic() throws Exception {
        List<Observation> observations = new ArrayList<>();
        int cleanCases = 0;
        long falsePositives = 0;
        for (int i = 0; i < 10; i++) {
            var app = new LoanApplication("SEEDED-" + i, "Synthetic Applicant", "4321",
                    new BigDecimal("145000"), 8, new BigDecimal("35000"), "home renovation", 60,
                    new BigDecimal("1200"), "Synthetic Employer", List.of("synthetic.pdf"));
            var reports = List.of(report("intake", AgentReport.Status.PASS, 10 + i),
                    report("kyc", AgentReport.Status.PASS, 10 + i),
                    report("credit-risk", AgentReport.Status.PASS, 20 + i),
                    report("compliance", AgentReport.Status.PASS, 20 + i));
            var env = policy.evaluate(app, reports);
            var clean = draft(app, LoanDecision.Decision.APPROVED, env.maxAmount(), env.apr(), "Checks completed.", reports);
            var cleanFacts = List.of(fact("intake", "employer", "Synthetic Employer"),
                    fact("credit-risk", "employer", "Synthetic Employer"));
            for (String defect : List.of("wrong-apr", "amount-over-cap", "approval-despite-fail",
                    "ungrounded-risk-score", "conflicting-fact")) {
                cleanCases++;
                if (!invariants.check(clean, env).isEmpty() || !detector.detect(clean, reports, cleanFacts).isEmpty()) {
                    falsePositives++;
                }
                LoanDecision seeded = clean;
                List<AgentReport> evidence = reports;
                List<Finding> findings = cleanFacts;
                String expected;
                switch (defect) {
                    case "wrong-apr" -> {
                        seeded = draft(app, LoanDecision.Decision.APPROVED, env.maxAmount(),
                                env.apr().add(BigDecimal.ONE), clean.reasoning(), reports);
                        expected = "APR_MISMATCH";
                    }
                    case "amount-over-cap" -> {
                        seeded = draft(app, LoanDecision.Decision.APPROVED, env.maxAmount().add(BigDecimal.ONE),
                                env.apr(), clean.reasoning(), reports);
                        expected = "AMOUNT_ABOVE_CAP";
                    }
                    case "approval-despite-fail" -> {
                        evidence = List.of(report("credit-risk", AgentReport.Status.FAIL, 80));
                        expected = ContradictionDetector.APPROVED_DESPITE_FAIL;
                    }
                    case "ungrounded-risk-score" -> {
                        seeded = draft(app, LoanDecision.Decision.APPROVED, env.maxAmount(), env.apr(),
                                "Risk score of 99 supports this decision.", reports);
                        expected = ContradictionDetector.UNGROUNDED_RISK_SCORE;
                    }
                    case "conflicting-fact" -> {
                        findings = List.of(cleanFacts.getFirst(), fact("credit-risk", "employer", "Other Synthetic Employer"));
                        expected = ContradictionDetector.CONFLICTING_FACT;
                    }
                    default -> throw new IllegalStateException("Unknown seeded defect");
                }
                var seededEnv = policy.evaluate(app, evidence);
                var violations = invariants.check(seeded, seededEnv);
                var issues = detector.detect(seeded, evidence, findings);
                var invariantRules = violations.stream().map(InvariantChecker.Violation::code).toList();
                var detectorRules = issues.stream().map(issue -> issue.ruleId()).toList();
                var merged = merge.merge(seeded, seededEnv, violations, false, app.applicantId(), evidence);
                var remainingInvariantRules = invariants.check(merged, seededEnv).stream()
                        .map(InvariantChecker.Violation::code).toList();
                var remainingDetectorRules = detector.detect(merged, evidence, findings).stream()
                        .map(issue -> issue.ruleId()).toList();
                observations.add(new Observation(app.applicantId() + "-" + defect, defect, expected,
                        invariantRules, detectorRules, invariantRules.contains(expected) || detectorRules.contains(expected),
                        remainingInvariantRules.isEmpty() && remainingDetectorRules.isEmpty(),
                        remainingInvariantRules, remainingDetectorRules));
            }
        }
        Map<String, Metrics> byDefect = new LinkedHashMap<>();
        observations.stream().map(Observation::defect).distinct().forEach(defect ->
                byDefect.put(defect, metrics(observations.stream().filter(o -> o.defect().equals(defect)).toList())));
        Metrics overall = metrics(observations);
        var report = new Report("seeded-defect-report-v1", "loan-seeded-defects-v1", "DETERMINISTIC_CODE_ONLY",
                cleanCases, falsePositives, (double) falsePositives / cleanCases, overall, Map.copyOf(byDefect),
                null, null, overall.postMergeCleanRate() >= .7, List.copyOf(observations),
                List.of("Balanced hand-authored synthetic defects, not the production inconsistency distribution.",
                        "Catch rate counts the expected rule, not an unrelated warning or conservative final decision.",
                        "Post-merge cleanliness exercises pure PolicyMerge; it does not authorize bypassing the critic or protocol.",
                        "Independent critic/judge performance, H5 production skip rate and live-model quality remain unmeasured.",
                        "Contradictory facts and ungrounded scores remain unresolved; the production critic and human gates stay enabled."));
        Files.createDirectories(Path.of("target"));
        new ObjectMapper().findAndRegisterModules().writerWithDefaultPrettyPrinter()
                .writeValue(Path.of("target/seeded-defect-report.json").toFile(), report);

        assertThat(report.pairedCleanCases()).isEqualTo(50);
        assertThat(report.cleanFalsePositives()).isZero();
        assertThat(report.overall().cases()).isEqualTo(50);
        assertThat(report.overall().caught()).isEqualTo(50);
        assertThat(report.overall().postMergeClean()).isEqualTo(30);
        assertThat(report.syntheticSeventyPercentRepairTargetMet()).isFalse();
        assertThat(report.independentCriticCatchRate()).isNull();
        assertThat(report.productionCriticCallsPerCase()).isNull();
        assertThat(report.byDefect()).hasSize(5);
        assertThat(report.observations()).allSatisfy(row -> assertThat(row.caught()).isTrue());
        assertThat(report.observations()).filteredOn(row -> row.defect().equals("conflicting-fact")
                || row.defect().equals("ungrounded-risk-score"))
                .allSatisfy(row -> assertThat(row.postMergeIssuesEmpty()).isFalse());
    }

    private Metrics metrics(List<Observation> rows) {
        long caught = rows.stream().filter(Observation::caught).count();
        long clean = rows.stream().filter(Observation::postMergeIssuesEmpty).count();
        return new Metrics(rows.size(), caught, (double) caught / rows.size(), clean, (double) clean / rows.size());
    }

    private AgentReport report(String agent, AgentReport.Status status, int score) {
        return new AgentReport(agent, status, score, "Synthetic evidence", List.of(), List.of());
    }

    private LoanDecision draft(LoanApplication app, LoanDecision.Decision decision, BigDecimal amount,
                               BigDecimal apr, String reasoning, List<AgentReport> reports) {
        return new LoanDecision(app.applicantId(), decision, amount, apr, List.of(), List.of(), reasoning, reports, 0, TIME);
    }

    private Finding fact(String agent, String key, Object value) {
        return new Finding("seed-" + agent + "-" + key, "SEEDED", "loan", agent,
                Finding.Kind.CHECK_RESULT, Finding.Status.PASS, Map.of(key, value), List.of(),
                "Synthetic finding", List.of(), List.of(), "HIGH", null,
                Provenance.of("offline-seed", false, 1), false, Finding.SCHEMA_VERSION, TIME);
    }
}
