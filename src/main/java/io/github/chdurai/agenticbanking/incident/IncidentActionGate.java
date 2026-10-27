package io.github.chdurai.agenticbanking.incident;

import io.github.chdurai.agenticbanking.knowledge.KnowledgeGraph;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Deterministic incident-autonomy gate.
 *
 * <p>It validates action ids/params against the catalog, derives risk in code from environment
 * and graph signals, and decides AUTO vs HITL/ESCALATE independent of LLM self-labels.</p>
 */
@Component
public class IncidentActionGate {

    public record GateDecision(IncidentResolution.Status status, List<RemediationPlan.Step> executableSteps,
                               List<String> reasons, String requiredRole, int blastRadius, int tier) {}

    private final IncidentActionCatalog catalog;
    private final IncidentPolicyProperties props;
    private final KnowledgeGraph graph;
    private final IncidentAutonomyLadder autonomyLadder;

    public IncidentActionGate(IncidentActionCatalog catalog, IncidentPolicyProperties props, KnowledgeGraph graph,
                              IncidentAutonomyLadder autonomyLadder) {
        this.catalog = catalog;
        this.props = props;
        this.graph = graph;
        this.autonomyLadder = autonomyLadder;
    }

    public GateDecision evaluate(Incident incident, IncidentTriage triage, DiagnosisReport diagnosis, RemediationPlan plan) {
        List<String> reasons = new ArrayList<>();
        Optional<KnowledgeGraph.Node> node = graph.resolve(incident.service());
        int tier = node.map(KnowledgeGraph.Node::tier).orElse(3);
        int blastRadius = node.map(n -> graph.blastRadius(n.id()).size()).orElse(0);
        boolean criticalProd = isProduction(incident.environment()) && tier < props.minTierForAutoInProduction();
        boolean freeze = props.inChangeFreeze(Instant.now());

        if (triage.severity() == IncidentTriage.Severity.SEV1 || triage.severity() == IncidentTriage.Severity.SEV2) {
            reasons.add("severity floor: " + triage.severity() + " always escalates");
        }
        if (triage.category() == IncidentTriage.Category.SECURITY) {
            reasons.add("security incidents always escalate");
        }
        if (diagnosis.confidencePct() < props.minConfidencePct()) {
            reasons.add("diagnosis confidence below floor: " + diagnosis.confidencePct() + "% < " + props.minConfidencePct() + "%");
        }
        if (plan.steps() == null || plan.steps().isEmpty()) {
            reasons.add("empty remediation plan");
            return new GateDecision(IncidentResolution.Status.ESCALATED, List.of(), reasons, null, blastRadius, tier);
        }

        List<RemediationPlan.Step> executable = new ArrayList<>();
        boolean invalid = false;
        boolean anyNeedsApproval = false;
        for (RemediationPlan.Step raw : plan.steps()) {
            IncidentActionCatalog.Validation v = catalog.validate(raw);
            if (!v.valid()) {
                reasons.add(v.reason());
                invalid = true;
                continue;
            }
            RemediationPlan.RiskLevel computed = computedRisk(v.def().baseRisk(), criticalProd, freeze,
                    blastRadius, props.maxAutoBlastRadius());
            boolean requiresApproval = computed != RemediationPlan.RiskLevel.LOW;
            if (criticalProd) {
                requiresApproval = true;
                reasons.add("production-critical service tier=" + tier + " requires approval");
            }
            if (freeze) {
                requiresApproval = true;
                reasons.add("change-freeze window active");
            }
            if (blastRadius > props.maxAutoBlastRadius()) {
                requiresApproval = true;
                reasons.add("blast radius " + blastRadius + " exceeds auto threshold " + props.maxAutoBlastRadius());
            }
            if (raw.riskLevel() != null && raw.riskLevel().ordinal() < computed.ordinal()) {
                reasons.add("LLM labeled " + raw.action() + " as " + raw.riskLevel()
                        + " but policy computed " + computed + " (LLM label cannot widen autonomy)");
            }
            if (raw.requiresApproval()) {
                reasons.add("LLM requested approval for action " + raw.action());
                requiresApproval = true;
            }
            anyNeedsApproval |= requiresApproval;
            executable.add(new RemediationPlan.Step(v.actionId(), v.params(), null, computed, requiresApproval));
        }

        if (invalid || !reasons.isEmpty() && reasons.stream().anyMatch(r -> r.startsWith("unknown action")
                || r.startsWith("unknown param") || r.startsWith("missing param") || r.startsWith("out-of-bounds"))) {
            return new GateDecision(IncidentResolution.Status.ESCALATED, List.of(), reasons, null, blastRadius, tier);
        }

        if (reasons.stream().anyMatch(r -> r.contains("always escalate") || r.startsWith("diagnosis confidence"))) {
            return new GateDecision(IncidentResolution.Status.ESCALATED, executable, reasons,
                    io.github.chdurai.agenticbanking.approval.Roles.INCIDENT_COMMANDER, blastRadius, tier);
        }
        IncidentAutonomyLadder.Decision autonomy = autonomyLadder.decide(triage);
        if (autonomy.demoted()) {
            reasons.add("autonomy class '" + autonomy.classKey() + "' regressed (auto rate "
                    + asPct(autonomy.autoRate()) + " over " + autonomy.sampleSize()
                    + " incidents, floor " + asPct(props.autonomyRegressionMinAutoRate())
                    + "); demoted from " + autonomy.configuredLevel() + " to " + autonomy.effectiveLevel());
        }
        if (!autonomy.allowsAuto()) {
            reasons.add("autonomy class '" + autonomy.classKey() + "' level " + autonomy.effectiveLevel()
                    + " blocks auto remediation (requires L2+)");
        }
        IncidentResolution.Status status = (anyNeedsApproval || !autonomy.allowsAuto())
                ? IncidentResolution.Status.PENDING_HUMAN_APPROVAL
                : IncidentResolution.Status.AUTO_REMEDIATED;
        return new GateDecision(status, List.copyOf(executable), reasons, io.github.chdurai.agenticbanking.approval.Roles.INCIDENT_COMMANDER,
                blastRadius, tier);
    }

    private static String asPct(double ratio) {
        return Math.round(ratio * 100) + "%";
    }

    private static RemediationPlan.RiskLevel computedRisk(RemediationPlan.RiskLevel base, boolean criticalProd,
                                                          boolean freeze, int blastRadius, int maxAutoBlastRadius) {
        RemediationPlan.RiskLevel r = base;
        if (criticalProd || freeze || blastRadius > maxAutoBlastRadius + 3) {
            r = max(r, RemediationPlan.RiskLevel.HIGH);
        } else if (blastRadius > maxAutoBlastRadius) {
            r = max(r, RemediationPlan.RiskLevel.MEDIUM);
        }
        return r;
    }

    private static RemediationPlan.RiskLevel max(RemediationPlan.RiskLevel a, RemediationPlan.RiskLevel b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }

    private static boolean isProduction(String environment) {
        return environment != null && environment.toLowerCase().contains("prod");
    }
}
