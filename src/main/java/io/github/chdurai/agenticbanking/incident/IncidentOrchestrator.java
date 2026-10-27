package io.github.chdurai.agenticbanking.incident;

import io.github.chdurai.agenticbanking.approval.ApprovalEvidencePacket;
import io.github.chdurai.agenticbanking.approval.ApprovalQueue;
import io.github.chdurai.agenticbanking.approval.ApprovalRequest;
import io.github.chdurai.agenticbanking.approval.RemediationExecutor;
import io.github.chdurai.agenticbanking.approval.Roles;
import io.github.chdurai.agenticbanking.cases.CaseService;
import io.github.chdurai.agenticbanking.flow.FlowRuntime;
import io.github.chdurai.agenticbanking.flow.plan.PlanResolver;
import io.github.chdurai.agenticbanking.guardrails.InputGuardrail;
import io.github.chdurai.agenticbanking.memory.EpisodicMemoryStore;
import io.github.chdurai.agenticbanking.ops.AgentMetrics;
import io.github.chdurai.agenticbanking.ops.PlatformEvent;
import io.github.chdurai.agenticbanking.ops.PlatformEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Incident pipeline: guardrails → triage (classify/severity/sentiment) →
 * RAG diagnosis → remediation planning → autonomous-execution gating.
 *
 * Autonomy policy: SEV1/SEV2 always escalate to humans. For lower severities, auto-remediation
 * is allowed only when the deterministic gate is safe and the class autonomy ladder is at L2+;
 * otherwise the plan waits for human sign-off.
 */
@Service
public class IncidentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(IncidentOrchestrator.class);

    private final IncidentTriageAgent triageAgent;
    private final IncidentDiagnosisAgent diagnosisAgent;
    private final IncidentRemediationAgent remediationAgent;
    private final InputGuardrail guardrail;
    private final IncidentMemoryStore memory;
    private final AgentMetrics metrics;
    private final FlowRuntime runtime;
    private final PlanResolver planResolver;
    private final ApprovalQueue approvals;
    private final RemediationExecutor executor;
    private final PlatformEventPublisher events;
    private final IncidentActionGate gate;
    private final IncidentActionCatalog catalog;
    private final IncidentRecoveryVerifier verifier;
    private final EpisodicMemoryStore episodic;
    @org.springframework.beans.factory.annotation.Autowired
    private io.github.chdurai.agenticbanking.llm.LlmProperties llm = io.github.chdurai.agenticbanking.llm.LlmProperties.defaults();

    public IncidentOrchestrator(IncidentTriageAgent triageAgent,
                                IncidentDiagnosisAgent diagnosisAgent,
                                IncidentRemediationAgent remediationAgent,
                                InputGuardrail guardrail,
                                IncidentMemoryStore memory,
                                AgentMetrics metrics,
                                FlowRuntime runtime,
                                PlanResolver planResolver,
                                ApprovalQueue approvals,
                                RemediationExecutor executor,
                                PlatformEventPublisher events,
                                IncidentActionGate gate,
                                IncidentActionCatalog catalog,
                                IncidentRecoveryVerifier verifier,
                                EpisodicMemoryStore episodic) {
        this.triageAgent = triageAgent;
        this.diagnosisAgent = diagnosisAgent;
        this.remediationAgent = remediationAgent;
        this.guardrail = guardrail;
        this.memory = memory;
        this.metrics = metrics;
        this.runtime = runtime;
        this.planResolver = planResolver;
        this.approvals = approvals;
        this.executor = executor;
        this.events = events;
        this.gate = gate;
        this.catalog = catalog;
        this.verifier = verifier;
        this.episodic = episodic;
    }

    public IncidentResolution handle(Incident raw) {
        try (var ignored = llm.budget().openForFlow("incident")) {
            return handleWithinBudget(raw);
        }
    }

    private IncidentResolution handleWithinBudget(Incident raw) {
        // 1. Guardrails on free-text fields
        List<String> flags = new ArrayList<>();
        var title = guardrail.screenText("title", raw.title());
        var desc = guardrail.screenText("description", raw.description());
        flags.addAll(title.flags());
        flags.addAll(desc.flags());
        if (title.blocked() || desc.blocked()) {
            metrics.recordGuardrailBlock("incident");
            log.warn("Incident {} blocked by guardrails: {}", raw.incidentId(), flags);
            IncidentResolution blocked = new IncidentResolution(raw.incidentId(),
                    IncidentResolution.Status.BLOCKED, null, null, null, flags,
                    "Blocked by input safety screening before agent processing.", List.of(), null,
                    Instant.now());
            memory.record(blocked);
            events.publish(PlatformEvent.of("incident.resolved", "incident", blocked.incidentId(),
                    blocked.status().name()));
            return blocked;
        }
        Incident incident = new Incident(raw.incidentId(), title.sanitized(), desc.sanitized(),
                raw.service(), raw.environment(), raw.reporter(), raw.tags());

        // 2. Triage → Diagnosis → Remediation, traced. Any agent outage fails safe:
        // the incident is escalated to humans rather than dropped or auto-actioned.
        FlowRuntime.DagRun<Object> run = runtime.run(raw.incidentId(), planResolver.rules("incident"),
                (agent, in) -> switch (agent) {
                    case "incident-triage" -> triageAgent.triage(incident);
                    case "incident-diagnosis" ->
                            diagnosisAgent.diagnose(incident, (IncidentTriage) in.get("incident-triage"));
                    case "incident-remediation" -> remediationAgent.plan(incident,
                            (IncidentTriage) in.get("incident-triage"),
                            (DiagnosisReport) in.get("incident-diagnosis"));
                    default -> throw new IllegalStateException("unexpected incident agent " + agent);
                }, null);
        FlowRuntime.NodeResult<Object> failure = run.firstFailure();
        if (failure != null || run.stopped()) {
            String why = failure != null ? failure.error().getMessage() : run.stopReason();
            flags.add("AGENT_UNAVAILABLE");
            log.error("Incident {} agent pipeline failed ({}). Escalating to humans.",
                    raw.incidentId(), why);
            List<String> details = List.of("Reason: agent pipeline unavailable - " + why,
                    "Title: " + incident.title(),
                    "Manual triage required; re-run the pipeline when the model recovers.");
            ApprovalRequest request = approvals.submit("incident", raw.incidentId(),
                    "Untriaged incident on " + raw.service(),
                    details,
                    incidentEvidencePacket(raw, null, null, null, details, "Pipeline unavailable; manual triage required"));
            IncidentResolution failed = new IncidentResolution(raw.incidentId(),
                    IncidentResolution.Status.ESCALATED, null, null, null, flags,
                    "AI pipeline unavailable; escalated for manual triage. No automated action taken.",
                    List.of(), request.id(), Instant.now());
            memory.record(failed);
            events.publish(PlatformEvent.of("incident.resolved", "incident", failed.incidentId(),
                    failed.status() + " (pipeline unavailable)"));
            return failed;
        }
        IncidentTriage triage = (IncidentTriage) run.value("incident-triage");
        DiagnosisReport diagnosis = (DiagnosisReport) run.value("incident-diagnosis");
        RemediationPlan plan = (RemediationPlan) run.value("incident-remediation");

        // 3. Deterministic action gate
        IncidentActionGate.GateDecision decision = gate.evaluate(incident, triage, diagnosis, plan);
        IncidentResolution.Status status = decision.status();
        String verificationNote = null;

        List<String> executionLog = List.of();
        String approvalId = null;
        if (status == IncidentResolution.Status.AUTO_REMEDIATED) {
            executionLog = executor.execute(incident.incidentId(), decision.executableSteps());
            IncidentRecoveryVerifier.Result verification = verifier.verify(incident, triage, diagnosis, executionLog);
            if (!verification.recovered()) {
                List<RemediationPlan.Step> rollback = rollbackSteps(decision.executableSteps());
                if (!rollback.isEmpty()) {
                    List<String> rollbackLog = executor.execute(incident.incidentId(), rollback);
                    List<String> merged = new ArrayList<>(executionLog);
                    merged.add("[VERIFY] failed: " + verification.reason());
                    merged.addAll(rollbackLog.stream().map(line -> line + " [ROLLBACK]").toList());
                    executionLog = List.copyOf(merged);
                }
                status = IncidentResolution.Status.ESCALATED;
                verificationNote = "post-remediation verification failed: " + verification.reason();
                List<String> details = approvalDetails(status, triage, diagnosis, decision);
                ApprovalRequest request = approvals.submit("incident", incident.incidentId(),
                        "%s %s: %s".formatted(triage.severity(), triage.category(), incident.title()),
                        details,
                        incidentEvidencePacket(incident, triage, diagnosis, decision, details,
                                "Verification failed; manual intervention required"));
                approvalId = request.id();
            } else {
                episodic.recordIncident(CaseService.pseudonym(incident.service()), incident.service(),
                        IncidentDiagnosisAgent.symptomKey(incident, triage),
                        "verified incident recovery after %s remediation".formatted(triage.category()), true);
            }
        } else {
            io.github.chdurai.agenticbanking.approval.ActionSpec action = status == IncidentResolution.Status.PENDING_HUMAN_APPROVAL
                    ? io.github.chdurai.agenticbanking.incident.RemediationActionHandler.spec(incident.incidentId(), decision.executableSteps())
                    : null;
            List<String> details = approvalDetails(status, triage, diagnosis, decision);
            ApprovalRequest request = approvals.submit("incident", incident.incidentId(),
                    "%s %s: %s".formatted(triage.severity(), triage.category(), incident.title()),
                    details,
                    incidentEvidencePacket(incident, triage, diagnosis, decision, details,
                            "Incident recommendation: " + status),
                    action, null, action == null ? null : Roles.INCIDENT_COMMANDER);
            approvalId = request.id();
        }
        String narrative = buildNarrative(status, triage, plan, diagnosis, decision, verificationNote);

        IncidentResolution resolution = new IncidentResolution(raw.incidentId(), status,
                triage, diagnosis, plan, flags, narrative, executionLog, approvalId, Instant.now());
        memory.record(resolution);
        metrics.recordIncident(triage.severity().name(), status.name());
        events.publish(PlatformEvent.of("incident.resolved", "incident", resolution.incidentId(),
                status + " severity=" + triage.severity()));
        log.info("Incident {} -> {} (severity={}, confidence={}%)",
                raw.incidentId(), status, triage.severity(), diagnosis.confidencePct());
        return resolution;
    }

    /** Legacy helper kept for direct unit tests that don't instantiate the full gate. */
    static IncidentResolution.Status determineStatus(IncidentTriage triage, RemediationPlan plan,
                                                     DiagnosisReport diagnosis) {
        if (triage.severity() == IncidentTriage.Severity.SEV1
                || triage.severity() == IncidentTriage.Severity.SEV2
                || triage.category() == IncidentTriage.Category.SECURITY
                || diagnosis.confidencePct() < 50) {
            return IncidentResolution.Status.ESCALATED;
        }
        return plan.isFullyAutonomous()
                ? IncidentResolution.Status.AUTO_REMEDIATED
                : IncidentResolution.Status.PENDING_HUMAN_APPROVAL;
    }

    private static List<String> approvalDetails(IncidentResolution.Status status, IncidentTriage triage,
                                                DiagnosisReport diagnosis, IncidentActionGate.GateDecision decision) {
        List<String> details = new ArrayList<>();
        details.add("Reason: " + status + " (confidence " + diagnosis.confidencePct() + "%)");
        details.add("Summary: " + triage.summary());
        details.add("Probable causes: " + String.join("; ", diagnosis.probableCauses()));
        details.add("Service tier: " + decision.tier() + ", blast radius: " + decision.blastRadius());
        if (decision.executableSteps() != null) {
            decision.executableSteps().forEach(s -> details.add("Step [%s%s]: %s %s".formatted(s.riskLevel(),
                    s.requiresApproval() ? ", approval" : "", s.action(), s.params())));
        }
        details.addAll(decision.reasons());
        return details;
    }

    private static String buildNarrative(IncidentResolution.Status status, IncidentTriage triage,
                                         RemediationPlan plan, DiagnosisReport diagnosis,
                                         IncidentActionGate.GateDecision decision,
                                         String verificationNote) {
        String base = switch (status) {
            case AUTO_REMEDIATED -> "Executed %d low-risk remediation steps autonomously (simulated executor). "
                    .formatted(decision.executableSteps().size())
                    + "Top cause: " + first(diagnosis.probableCauses());
            case PENDING_HUMAN_APPROVAL -> "Remediation plan ready (%d steps, ~%d min); "
                    .formatted(decision.executableSteps().size(), plan.estimatedMinutes())
                    + "awaiting human approval in /api/approvals.";
            case ESCALATED -> "Escalated to on-call (review queued): severity=%s, category=%s, confidence=%d%%. %s"
                    .formatted(triage.severity(), triage.category(), diagnosis.confidencePct(),
                            String.join("; ", decision.reasons()));
            case BLOCKED -> "Blocked by input guardrails.";
        };
        if (verificationNote == null || verificationNote.isBlank()) {
            return base;
        }
        return base + " " + verificationNote;
    }

    private static ApprovalEvidencePacket incidentEvidencePacket(Incident incident,
                                                                 IncidentTriage triage,
                                                                 DiagnosisReport diagnosis,
                                                                 IncidentActionGate.GateDecision decision,
                                                                 List<String> details,
                                                                 String recommendation) {
        List<ApprovalEvidencePacket.AgentFinding> findings = new ArrayList<>();
        if (triage != null) {
            findings.add(new ApprovalEvidencePacket.AgentFinding(
                    "incident-triage", "PASS",
                    triage.severity() == IncidentTriage.Severity.SEV1 ? 90
                            : triage.severity() == IncidentTriage.Severity.SEV2 ? 75 : 50,
                    triage.summary(), List.of()));
        }
        if (diagnosis != null) {
            findings.add(new ApprovalEvidencePacket.AgentFinding(
                    "incident-diagnosis", "PASS", diagnosis.confidencePct(),
                    String.join("; ", diagnosis.probableCauses()),
                    diagnosis.matchedRunbooks() == null ? List.of() : diagnosis.matchedRunbooks()));
        }
        if (decision != null && decision.executableSteps() != null && !decision.executableSteps().isEmpty()) {
            findings.add(new ApprovalEvidencePacket.AgentFinding(
                    "incident-remediation", "PASS",
                    decision.executableSteps().stream()
                            .anyMatch(s -> s.riskLevel() != RemediationPlan.RiskLevel.LOW || s.requiresApproval()) ? 80 : 40,
                    "Planned " + decision.executableSteps().size() + " remediation steps",
                    decision.executableSteps().stream().map(RemediationPlan.Step::action).toList()));
        }
        List<String> minimizedFacts = List.of(
                "incidentId=" + incident.incidentId(),
                "service=" + incident.service(),
                "environment=" + incident.environment());
        List<String> policy = decision == null ? List.of() : List.copyOf(decision.reasons());
        List<String> invariants = details.stream().filter(d -> d.startsWith("Step [")).toList();
        List<String> uncertainty = new ArrayList<>();
        if (triage != null && (triage.severity() == IncidentTriage.Severity.SEV1
                || triage.severity() == IncidentTriage.Severity.SEV2)) {
            uncertainty.add("High severity incident requires strict human oversight");
        }
        if (diagnosis != null && diagnosis.confidencePct() < 60) {
            uncertainty.add("Low diagnosis confidence (" + diagnosis.confidencePct() + "%)");
        }
        ApprovalEvidencePacket.SideEffectPreview sideEffects = new ApprovalEvidencePacket.SideEffectPreview(
                List.of("Approve executes remediation plan with recorded execution log."),
                List.of("Reject/escalate keeps incident in manual handling queue."));
        int maxRisk = findings.stream().mapToInt(ApprovalEvidencePacket.AgentFinding::riskScore).max().orElse(0);
        boolean nonTrivial = maxRisk >= 60 || !uncertainty.isEmpty();
        return new ApprovalEvidencePacket(1, "incident", incident.incidentId(), minimizedFacts,
                findings, policy, invariants, "n/a", List.of("catalog:v1"), sideEffects,
                uncertainty, recommendation, maxRisk, nonTrivial);
    }

    private List<RemediationPlan.Step> rollbackSteps(List<RemediationPlan.Step> executed) {
        List<RemediationPlan.Step> rollback = new ArrayList<>();
        for (RemediationPlan.Step step : executed) {
            catalog.find(step.action()).ifPresent(def -> {
                if (!"none".equalsIgnoreCase(def.rollbackActionId())) {
                    rollback.add(new RemediationPlan.Step(def.rollbackActionId(), step.params(), null,
                            RemediationPlan.RiskLevel.HIGH, true));
                }
            });
        }
        return rollback;
    }

    private static String first(List<String> items) {
        return items == null || items.isEmpty() ? "unknown" : items.getFirst();
    }
}
