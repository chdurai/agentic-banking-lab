package io.github.chdurai.agenticbanking.incident;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AutonomyGatingTest {

    private static IncidentTriage triage(IncidentTriage.Severity sev, IncidentTriage.Category cat) {
        return new IncidentTriage(cat, sev, IncidentTriage.Sentiment.CALM, "s", "r");
    }

    private static RemediationPlan plan(RemediationPlan.Step... steps) {
        return new RemediationPlan(List.of(steps), 10, "rollback");
    }

    private static RemediationPlan.Step step(String action, Map<String, String> params,
                                             RemediationPlan.RiskLevel risk, boolean approval) {
        return new RemediationPlan.Step(action, params, null, risk, approval);
    }

    private static DiagnosisReport diagnosis(int confidence) {
        return new DiagnosisReport(List.of("cause"), List.of("ev"), List.of("RB-DB-001 §2"), confidence);
    }

    private static Incident incident(String service, String env) {
        return new Incident("INC-1", "t", "d", service, env, "sre", List.of("x"));
    }

    private static IncidentActionGate gate(IncidentPolicyProperties props) {
        return gate(props, levels("default", "L1", "sev3", "L2", "sev4", "L2"), new IncidentMemoryStore());
    }

    private static IncidentPolicyProperties policy(int maxBlastRadius, boolean freezeOn) {
        return new IncidentPolicyProperties(50, maxBlastRadius, 3, freezeOn,
                List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"),
                "00:00", "23:59", 25, 8, 0.55);
    }

    private static IncidentActionGate gate(IncidentPolicyProperties props, Map<String, String> levels,
                                           IncidentMemoryStore memory) {
        IncidentAutonomyProperties autonomy = new IncidentAutonomyProperties();
        levels.forEach((key, level) -> {
            IncidentAutonomyProperties.ClassPolicy p = new IncidentAutonomyProperties.ClassPolicy();
            p.setLevel(AutonomyLevel.valueOf(level));
            autonomy.put(key, p);
        });
        IncidentAutonomyLadder ladder = new IncidentAutonomyLadder(autonomy, props, memory);
        return new IncidentActionGate(new IncidentActionCatalog(), props, new io.github.chdurai.agenticbanking.knowledge.KnowledgeGraph(), ladder);
    }

    private static Map<String, String> levels(String... kv) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            out.put(kv[i], kv[i + 1]);
        }
        return out;
    }

    private static void record(IncidentMemoryStore memory, String id, IncidentTriage triage,
                               IncidentResolution.Status status) {
        memory.record(new IncidentResolution(id, status, triage, diagnosis(85), plan(
                step("kill_idle_sessions", Map.of("maxSessions", "20"), RemediationPlan.RiskLevel.LOW, false)),
                List.of(), "n", List.of(), null, Instant.now()));
    }

    @Test
    void sev3WithCatalogActionAutoRemediatesWhenSafe() {
        var d = gate(policy(10, false)).evaluate(
                incident("loan-service", "staging"),
                triage(IncidentTriage.Severity.SEV3, IncidentTriage.Category.DATABASE),
                diagnosis(85),
                plan(step("kill_idle_sessions", Map.of("maxSessions", "20"), RemediationPlan.RiskLevel.LOW, false)));
        assertThat(d.status()).isEqualTo(IncidentResolution.Status.AUTO_REMEDIATED);
    }

    @Test
    void sev1AlwaysEscalates() {
        var d = gate(policy(10, false)).evaluate(
                incident("loan-service", "staging"),
                triage(IncidentTriage.Severity.SEV1, IncidentTriage.Category.APPLICATION),
                diagnosis(95),
                plan(step("restart_pod", Map.of("maxPods", "1"), RemediationPlan.RiskLevel.LOW, false)));
        assertThat(d.status()).isEqualTo(IncidentResolution.Status.ESCALATED);
    }

    @Test
    void unknownActionIsNotExecutable() {
        var d = gate(policy(10, false)).evaluate(
                incident("loan-service", "staging"),
                triage(IncidentTriage.Severity.SEV3, IncidentTriage.Category.DATABASE),
                diagnosis(90),
                plan(step("drop_database", Map.of(), RemediationPlan.RiskLevel.LOW, false)));
        assertThat(d.status()).isEqualTo(IncidentResolution.Status.ESCALATED);
        assertThat(d.executableSteps()).isEmpty();
    }

    @Test
    void outOfBoundsParamsAreNotExecutable() {
        var d = gate(policy(10, false)).evaluate(
                incident("loan-service", "staging"),
                triage(IncidentTriage.Severity.SEV3, IncidentTriage.Category.DATABASE),
                diagnosis(90),
                plan(step("restart_pod", Map.of("maxPods", "99"), RemediationPlan.RiskLevel.LOW, false)));
        assertThat(d.status()).isEqualTo(IncidentResolution.Status.ESCALATED);
        assertThat(d.executableSteps()).isEmpty();
    }

    @Test
    void hostilePlanLabelCannotWidenAutonomy() {
        var d = gate(policy(10, false)).evaluate(
                incident("loan-service", "staging"),
                triage(IncidentTriage.Severity.SEV3, IncidentTriage.Category.DATABASE),
                diagnosis(80),
                plan(step("rollback_deploy", Map.of("maxRevisions", "1"), RemediationPlan.RiskLevel.LOW, false)));
        assertThat(d.status()).isEqualTo(IncidentResolution.Status.PENDING_HUMAN_APPROVAL);
    }

    @Test
    void blastRadiusThresholdDemotesToHumanApproval() {
        var d = gate(policy(0, false)).evaluate(
                incident("loan-service", "staging"),
                triage(IncidentTriage.Severity.SEV3, IncidentTriage.Category.DATABASE),
                diagnosis(90),
                plan(step("kill_idle_sessions", Map.of("maxSessions", "20"), RemediationPlan.RiskLevel.LOW, false)));
        assertThat(d.status()).isEqualTo(IncidentResolution.Status.PENDING_HUMAN_APPROVAL);
    }

    @Test
    void freezeWindowDemotesToHumanApproval() {
        var d = gate(policy(10, true)).evaluate(
                incident("loan-service", "staging"),
                triage(IncidentTriage.Severity.SEV3, IncidentTriage.Category.DATABASE),
                diagnosis(90),
                plan(step("kill_idle_sessions", Map.of("maxSessions", "20"), RemediationPlan.RiskLevel.LOW, false)));
        assertThat(d.status()).isEqualTo(IncidentResolution.Status.PENDING_HUMAN_APPROVAL);
    }

    @Test
    void l1ClassBlocksAutoRemediation() {
        IncidentPolicyProperties props = policy(10, false);
        var d = gate(props, levels("default", "L1", "sev3", "L1"), new IncidentMemoryStore()).evaluate(
                incident("loan-service", "staging"),
                triage(IncidentTriage.Severity.SEV3, IncidentTriage.Category.DATABASE),
                diagnosis(90),
                plan(step("kill_idle_sessions", Map.of("maxSessions", "20"), RemediationPlan.RiskLevel.LOW, false)));
        assertThat(d.status()).isEqualTo(IncidentResolution.Status.PENDING_HUMAN_APPROVAL);
        assertThat(d.reasons()).anyMatch(r -> r.contains("blocks auto remediation"));
    }

    @Test
    void categorySpecificClassCanPromoteOnlyMatchingIncidents() {
        IncidentPolicyProperties props = policy(10, false);
        Map<String, String> classLevels = levels(
                "default", "L1",
                "database", "L2"
        );
        IncidentActionGate gate = gate(props, classLevels, new IncidentMemoryStore());

        var db = gate.evaluate(
                incident("loan-service", "staging"),
                triage(IncidentTriage.Severity.SEV3, IncidentTriage.Category.DATABASE),
                diagnosis(90),
                plan(step("kill_idle_sessions", Map.of("maxSessions", "20"), RemediationPlan.RiskLevel.LOW, false)));
        var app = gate.evaluate(
                incident("loan-service", "staging"),
                triage(IncidentTriage.Severity.SEV3, IncidentTriage.Category.APPLICATION),
                diagnosis(90),
                plan(step("kill_idle_sessions", Map.of("maxSessions", "20"), RemediationPlan.RiskLevel.LOW, false)));

        assertThat(db.status()).isEqualTo(IncidentResolution.Status.AUTO_REMEDIATED);
        assertThat(app.status()).isEqualTo(IncidentResolution.Status.PENDING_HUMAN_APPROVAL);
    }

    @Test
    void regressionDemotesAnL2ClassToHumanApproval() {
        IncidentPolicyProperties props = new IncidentPolicyProperties(50, 10, 3, false,
                List.of("MONDAY"), "00:00", "23:59", 20, 6, 0.70);
        IncidentMemoryStore history = new IncidentMemoryStore();
        IncidentTriage triage = triage(IncidentTriage.Severity.SEV3, IncidentTriage.Category.DATABASE);
        for (int i = 0; i < 6; i++) {
            record(history, "H-" + i, triage, IncidentResolution.Status.PENDING_HUMAN_APPROVAL);
        }
        IncidentActionGate gate = gate(props, levels("default", "L1", "sev3", "L2"), history);

        var d = gate.evaluate(
                incident("loan-service", "staging"),
                triage,
                diagnosis(90),
                plan(step("kill_idle_sessions", Map.of("maxSessions", "20"), RemediationPlan.RiskLevel.LOW, false)));

        assertThat(d.status()).isEqualTo(IncidentResolution.Status.PENDING_HUMAN_APPROVAL);
        assertThat(d.reasons()).anyMatch(r -> r.contains("regressed"));
    }
}
