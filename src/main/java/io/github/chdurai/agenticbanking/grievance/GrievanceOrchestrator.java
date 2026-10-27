package io.github.chdurai.agenticbanking.grievance;

import io.github.chdurai.agenticbanking.approval.ApprovalEvidencePacket;
import io.github.chdurai.agenticbanking.approval.ApprovalQueue;
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
 * Grievance pipeline: sensitive-case circuit breaker (no LLM) → guardrails → triage →
 * deterministic routing/SLA → playbook-grounded response draft → human review gate.
 *
 * The LLM classifies and drafts; routing, SLA and the human-review decision are policy code.
 * Nothing is ever sent to a complainant by this service.
 */
@Service
public class GrievanceOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(GrievanceOrchestrator.class);
    private static final int MAX_DESCRIPTION = 4000;

    private static final String CONFIDENTIAL_ACK = "Your report has been received and will be handled "
            + "confidentially by a specially trained team. You do not need to repeat it. "
            + "You may ask for a safe contact channel at any time.";

    private final GrievanceTriageAgent triageAgent;
    private final GrievanceResponseAgent responseAgent;
    private final InputGuardrail guardrail;
    private final GrievanceMemoryStore memory;
    private final ApprovalQueue approvals;
    private final EpisodicMemoryStore episodic;
    private final AgentMetrics metrics;
    private final FlowRuntime runtime;
    private final PlanResolver planResolver;
    private final PlatformEventPublisher events;
    @org.springframework.beans.factory.annotation.Autowired
    private io.github.chdurai.agenticbanking.llm.LlmProperties llm = io.github.chdurai.agenticbanking.llm.LlmProperties.defaults();

    public GrievanceOrchestrator(GrievanceTriageAgent triageAgent, GrievanceResponseAgent responseAgent,
                                 InputGuardrail guardrail, GrievanceMemoryStore memory,
                                 ApprovalQueue approvals, EpisodicMemoryStore episodic,
                                 AgentMetrics metrics, FlowRuntime runtime,
                                 PlanResolver planResolver, PlatformEventPublisher events) {
        this.triageAgent = triageAgent;
        this.responseAgent = responseAgent;
        this.guardrail = guardrail;
        this.memory = memory;
        this.approvals = approvals;
        this.episodic = episodic;
        this.metrics = metrics;
        this.runtime = runtime;
        this.planResolver = planResolver;
        this.events = events;
    }

    public GrievanceResolution handle(Grievance raw) {
        try (var ignored = llm.budget().openForFlow("grievance")) {
            return handleWithinBudget(raw);
        }
    }

    private GrievanceResolution handleWithinBudget(Grievance raw) {
        // 1. Circuit breaker: sensitive cases never reach a general-purpose LLM and are never stored.
        if (SensitiveCaseDetector.isSensitive(raw.description(), raw.location())) {
            return confidential(raw);
        }

        // 2. Guardrails
        List<String> flags = new ArrayList<>();
        var desc = guardrail.screenText("description", raw.description(), MAX_DESCRIPTION);
        var location = guardrail.screenText("location", raw.location());
        var channel = guardrail.screenText("channel", raw.channel());
        flags.addAll(desc.flags());
        flags.addAll(location.flags());
        flags.addAll(channel.flags());
        if (desc.blocked() || location.blocked() || channel.blocked()) {
            metrics.recordGuardrailBlock("grievance");
            return finish(new GrievanceResolution(raw.grievanceId(), raw.projectId(),
                    GrievanceResolution.Status.BLOCKED, GrievanceResolution.Route.NONE, 0, null, null, flags,
                    "Blocked by input safety screening before agent processing.", null, Instant.now()));
        }
        Grievance g = new Grievance(raw.grievanceId(), raw.projectId(), desc.sanitized(), channel.sanitized(),
                location.sanitized(), raw.anonymous());

        // No deterministic lexicon covers this script, so the sensitive-case breaker cannot vouch for it.
        if (io.github.chdurai.agenticbanking.guardrails.TextNormalizer.mostlyUncoveredScript(g.description())) {
            flags.add("UNSUPPORTED_LANGUAGE");
            return humanFallback(g, flags, "Language not covered by automated safety screening; routed to a human reviewer.");
        }

        // 3. Triage, then (deterministic routing between them) the playbook-grounded draft.
        // Triage failure fails safe to a human; a draft failure is non-critical: the case is still routed.
        FlowRuntime.DagRun<Object> run = runtime.run(g.grievanceId(), planResolver.rules("grievance"),
                (agent, in) -> switch (agent) {
                    case "grievance-triage" -> triageAgent.triage(g);
                    case "grievance-response" -> {
                        GrievanceTriage t = (GrievanceTriage) in.get("grievance-triage");
                        yield responseAgent.draft(g, t, GrievanceRouting.decide(t));
                    }
                    default -> throw new IllegalStateException("unexpected grievance agent " + agent);
                }, null);
        GrievanceTriage triage = (GrievanceTriage) run.value("grievance-triage");
        if (triage == null) {
            flags.add("TRIAGE_UNAVAILABLE");
            return humanFallback(g, flags, "Automated triage unavailable; routed to a safeguards specialist.");
        }

        // 4. Deterministic routing / SLA
        GrievanceRouting.Decision routing = GrievanceRouting.decide(triage);

        // 5. Draft
        GrievanceResponseDraft draft = (GrievanceResponseDraft) run.value("grievance-response");
        if (draft == null) {
            flags.add("DRAFT_UNAVAILABLE");
        }

        boolean needsReview = routing.humanReviewRequired() || draft == null;
        GrievanceResolution.Status status = needsReview
                ? GrievanceResolution.Status.PENDING_HUMAN_REVIEW : GrievanceResolution.Status.ROUTED;

        String approvalId = null;
        if (needsReview) {
            List<String> details = approvalDetails(triage, routing, draft);
            approvalId = approvals.submit("grievance", g.grievanceId(),
                    "%s grievance on %s -> %s".formatted(triage.severity(), g.projectId(), routing.route()),
                    details,
                    grievanceEvidencePacket(g, triage, routing, draft, details, "Human review required")).id();
        }

        String narrative = ("%s: routed to %s with a %d-hour response target (%s). "
                + (needsReview ? "A person must review the draft before anything is sent."
                : "Draft ready for the case officer."))
                .formatted(status, routing.route(), routing.slaHours(), routing.reason());
        return finish(new GrievanceResolution(g.grievanceId(), g.projectId(), status, routing.route(),
                routing.slaHours(), triage, draft, flags, narrative, approvalId, Instant.now()));
    }

    private GrievanceResolution confidential(Grievance raw) {
        metrics.recordCounter("grievance.sensitive_bypass", "flow", "grievance");
        String approvalId = approvals.submit("grievance", raw.grievanceId(),
                "CONFIDENTIAL case on " + raw.projectId() + " - restricted handling",
                List.of("Sensitive-case circuit breaker triggered; no automated processing performed.",
                        "Original report is NOT stored here: retrieve it from the source channel.",
                        "Follow survivor-centred procedure; contact only via a safe channel."),
                grievanceEvidencePacket(raw, null, null, null,
                        List.of("Sensitive-case circuit breaker triggered; no automated processing performed."),
                        "Confidential escalation"), null, null, io.github.chdurai.agenticbanking.approval.Roles.SENSITIVE_CASE_TEAM).id();
        GrievanceResponseDraft ack = new GrievanceResponseDraft(CONFIDENTIAL_ACK, List.of(), List.of(),
                "Confidential case team contacts the complainant within 24 hours via a safe channel.");
        log.warn("Grievance {} diverted to confidential handling (no LLM, no content stored)", raw.grievanceId());
        return finish(new GrievanceResolution(raw.grievanceId(), raw.projectId(),
                GrievanceResolution.Status.CONFIDENTIAL_ESCALATION,
                GrievanceResolution.Route.CONFIDENTIAL_CASE_TEAM, 24, null, ack,
                List.of("SENSITIVE_CASE_BYPASS"),
                "Sensitive case: bypassed all AI processing and routed to the confidential case team.",
                approvalId, Instant.now()));
    }

    private GrievanceResolution humanFallback(Grievance g, List<String> flags, String narrative) {
        List<String> details = List.of("Reason: " + narrative, "Summary unavailable - open the original grievance.");
        String approvalId = approvals.submit("grievance", g.grievanceId(),
                "Untriaged grievance on " + g.projectId(), details,
                grievanceEvidencePacket(g, null, null, null, details, "Manual review fallback")).id();
        return finish(new GrievanceResolution(g.grievanceId(), g.projectId(),
                GrievanceResolution.Status.PENDING_HUMAN_REVIEW,
                GrievanceResolution.Route.SAFEGUARDS_SPECIALIST, 72, null, null, flags, narrative, approvalId,
                Instant.now()));
    }

    private GrievanceResolution finish(GrievanceResolution resolution) {
        if (resolution.status() == GrievanceResolution.Status.ROUTED
                || resolution.status() == GrievanceResolution.Status.CONFIDENTIAL_ESCALATION) {
            String subjectRef = CaseService.pseudonym(resolution.projectId());
            episodic.recordGrievancePattern(subjectRef, resolution.projectId(), resolution.route().name(),
                    "%s grievance routed to %s with %dh SLA".formatted(
                            resolution.status(), resolution.route(), resolution.slaHours()));
        }
        memory.record(resolution);
        metrics.recordCounter("grievance.handled", "status", resolution.status().name());
        events.publish(PlatformEvent.of("grievance.handled", "grievance", resolution.grievanceId(),
                resolution.status() + " -> " + resolution.route()));
        log.info("Grievance {} -> {} ({})", resolution.grievanceId(), resolution.status(), resolution.route());
        return resolution;
    }

    private static List<String> approvalDetails(GrievanceTriage triage, GrievanceRouting.Decision routing,
                                                GrievanceResponseDraft draft) {
        List<String> details = new ArrayList<>();
        details.add("Reason: " + routing.reason() + " (SLA " + routing.slaHours() + "h)");
        details.add("Themes: " + triage.themes() + ", severity " + triage.severity()
                + ", sentiment " + triage.sentiment() + ", confidence " + triage.confidencePct() + "%");
        details.add("Summary: " + triage.summary());
        if (draft != null && draft.proposedActions() != null) {
            draft.proposedActions().forEach(a -> details.add("Proposed: " + a));
        } else {
            details.add("No AI draft available.");
        }
        return details;
    }

    private static ApprovalEvidencePacket grievanceEvidencePacket(Grievance g,
                                                                  GrievanceTriage triage,
                                                                  GrievanceRouting.Decision routing,
                                                                  GrievanceResponseDraft draft,
                                                                  List<String> details,
                                                                  String recommendation) {
        List<ApprovalEvidencePacket.AgentFinding> findings = new ArrayList<>();
        if (triage != null) {
            findings.add(new ApprovalEvidencePacket.AgentFinding(
                    "grievance-triage", "PASS", triage.confidencePct(), triage.summary(),
                    triage.themes() == null ? List.of() : triage.themes().stream().map(Enum::name).toList()));
        }
        if (draft != null) {
            findings.add(new ApprovalEvidencePacket.AgentFinding(
                    "grievance-response", "PASS", 40, "Draft acknowledgement and follow-up plan",
                    draft.citedPlaybooks() == null ? List.of() : draft.citedPlaybooks()));
        }
        int maxRisk = triage == null ? 70 : switch (triage.severity()) {
            case CRITICAL -> 95;
            case HIGH -> 80;
            case MODERATE -> 60;
            case LOW -> 35;
        };
        List<String> uncertainty = new ArrayList<>();
        if (triage == null || triage.confidencePct() < GrievanceRouting.MIN_CONFIDENCE) {
            uncertainty.add("Low or unavailable triage confidence");
        }
        if (triage != null && (triage.retaliationRisk() || triage.vulnerableGroupsAffected())) {
            uncertainty.add("Protection-risk indicators present");
        }
        ApprovalEvidencePacket.SideEffectPreview sideEffects = new ApprovalEvidencePacket.SideEffectPreview(
                List.of("Approve routes case per grievance policy with officer-tracked closure."),
                List.of("Reject keeps case in safeguards queue; no automated complainant response."));
        boolean nonTrivial = routing == null || routing.humanReviewRequired() || !uncertainty.isEmpty() || maxRisk >= 60;
        return new ApprovalEvidencePacket(1, "grievance", g.grievanceId(),
                List.of("grievanceId=" + g.grievanceId(), "projectId=" + g.projectId(), "anonymous=" + g.anonymous()),
                findings,
                routing == null ? List.of() : List.of("route=" + routing.route(), "slaHours=" + routing.slaHours(),
                        "reason=" + routing.reason()),
                details, "n/a", List.of("grievance-routing:v1"), sideEffects, uncertainty, recommendation,
                maxRisk, nonTrivial);
    }
}
