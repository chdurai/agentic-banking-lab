package io.github.chdurai.agenticbanking.orchestrator;

import io.github.chdurai.agenticbanking.agents.DecisionAgent;
import io.github.chdurai.agenticbanking.approval.ApprovalEvidencePacket;
import io.github.chdurai.agenticbanking.approval.ApprovalQueue;
import io.github.chdurai.agenticbanking.agents.LoanAgent;
import io.github.chdurai.agenticbanking.agents.PlannerAgent;
import io.github.chdurai.agenticbanking.config.AgentProperties;
import io.github.chdurai.agenticbanking.flow.FlowRuntime;
import io.github.chdurai.agenticbanking.flow.plan.DagPlan;
import io.github.chdurai.agenticbanking.flow.plan.PlanResolver;
import io.github.chdurai.agenticbanking.flow.state.Provenance;
import io.github.chdurai.agenticbanking.guardrails.GuardrailResult;
import io.github.chdurai.agenticbanking.guardrails.InputGuardrail;
import io.github.chdurai.agenticbanking.memory.CaseMemoryStore;
import io.github.chdurai.agenticbanking.model.AgentReport;
import io.github.chdurai.agenticbanking.model.LoanApplication;
import io.github.chdurai.agenticbanking.model.LoanContext;
import io.github.chdurai.agenticbanking.model.LoanDecision;
import io.github.chdurai.agenticbanking.ops.AgentMetrics;
import io.github.chdurai.agenticbanking.policy.InvariantChecker;
import io.github.chdurai.agenticbanking.policy.LoanPolicyEngine;
import io.github.chdurai.agenticbanking.policy.PolicyEnvelope;
import io.github.chdurai.agenticbanking.policy.PolicyMerge;
import io.github.chdurai.agenticbanking.flow.protocol.ChallengeCoordinator;
import io.github.chdurai.agenticbanking.reflection.CriticAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Supervisor orchestrator implementing the full agentic loop:
 *
 *   guardrails → planner (dynamic DAG) → staged parallel execution
 *   → decision → critic (reflection, bounded revisions) → memory record
 *
 * Agents within a stage run concurrently on virtual threads. Any hard FAIL above
 * the configured threshold fail-fasts the remaining stages.
 */
@Service
public class LoanOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(LoanOrchestrator.class);

    private final Map<String, LoanAgent> agentsByName;
    private final PlannerAgent planner;
    private final DecisionAgent decisionAgent;
    private final CriticAgent critic;
    private final InputGuardrail guardrail;
    private final CaseMemoryStore memory;
    private final AgentProperties properties;
    private final AgentMetrics metrics;
    private final ApprovalQueue approvals;
    private final io.github.chdurai.agenticbanking.ops.PlatformEventPublisher events;
    private final LoanPolicyEngine policy;
    private final InvariantChecker invariants;
    private final PolicyMerge merger;
    private final io.github.chdurai.agenticbanking.flow.FlowKit flowKit;
    private final FlowRuntime runtime;
    private final PlanResolver planResolver;
    private final ChallengeCoordinator challenges;
    @org.springframework.beans.factory.annotation.Autowired
    private io.github.chdurai.agenticbanking.llm.LlmProperties llm = io.github.chdurai.agenticbanking.llm.LlmProperties.defaults();
    @org.springframework.beans.factory.annotation.Value("${platform.loan.recovery-simulation:false}")
    private boolean recoverySimulation;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private io.github.chdurai.agenticbanking.investigation.LoanInvestigator investigator;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private io.github.chdurai.agenticbanking.investigation.InvestigationStore investigationStore;

    public LoanOrchestrator(List<LoanAgent> agents, PlannerAgent planner,
                            DecisionAgent decisionAgent, CriticAgent critic,
                            InputGuardrail guardrail, CaseMemoryStore memory,
                            AgentProperties properties, AgentMetrics metrics,
                            FlowRuntime runtime, PlanResolver planResolver, ApprovalQueue approvals,
                            io.github.chdurai.agenticbanking.ops.PlatformEventPublisher events,
                            LoanPolicyEngine policy, InvariantChecker invariants, PolicyMerge merger,
                            io.github.chdurai.agenticbanking.flow.FlowKit flowKit, ChallengeCoordinator challenges) {
        this.agentsByName = agents.stream()
                .collect(Collectors.toMap(LoanAgent::agentName, Function.identity(), (a, b) -> {
                    throw new IllegalStateException("Duplicate loan agent name '" + a.agentName() + "': "
                            + a.getClass().getSimpleName() + " and " + b.getClass().getSimpleName());
                }));
        this.planner = planner;
        this.decisionAgent = decisionAgent;
        this.critic = critic;
        this.guardrail = guardrail;
        this.memory = memory;
        this.properties = properties;
        this.metrics = metrics;
        this.runtime = runtime;
        this.planResolver = planResolver;
        this.approvals = approvals;
        this.events = events;
        this.policy = policy;
        this.invariants = invariants;
        this.merger = merger;
        this.flowKit = flowKit;
        this.challenges = challenges;
        flowKit.specs().planned("loan").forEach(spec -> {
            if (!agentsByName.containsKey(spec.agentId())) {
                throw new IllegalStateException("AgentSpec '" + spec.agentId() + "' (flow loan) has no LoanAgent bean");
            }
        });
        log.info("Agent registry: {}", agentsByName.keySet());
    }

    public LoanDecision evaluate(LoanApplication rawApplication) {
        try (var ignored = llm.budget().openForFlow("loan")) {
            if (investigationStore != null) investigationStore.captureContext(rawApplication, policy.properties());
            LoanDecision decision = evaluateWithinBudget(rawApplication);
            if (investigationStore != null) investigationStore.sealSources(io.github.chdurai.agenticbanking.cases.CaseExecution.current());
            return decision;
        }
    }

    private LoanDecision evaluateWithinBudget(LoanApplication rawApplication) {
        // 1. Guardrails - nothing reaches an LLM unscreened
        GuardrailResult screened = guardrail.screen(rawApplication);
        if (!screened.flags().isEmpty()) {
            log.warn("Guardrail flags: {}", screened.flags());
        }
        if (screened.secondLine()) {
            metrics.recordCounter("guardrail.second_line", "flow", "loan");
        }
        if (screened.blocked()) {
            metrics.recordGuardrailBlock("loan");
            LoanDecision blocked = blockedDecision(rawApplication, screened.flags());
            memory.record(blocked);
            metrics.recordDecision(blocked.decision().name());
            events.publish(io.github.chdurai.agenticbanking.ops.PlatformEvent.of("loan.decided", "loan", blocked.applicationId(),
                    "DECLINED (input guardrail block)"));
            return blocked;
        }
        LoanApplication application = screened.sanitizedApplication();
        String memorySummary = memory.summarizeHistory(application.applicantId());

        // 2. Dynamic plan (validated; falls back to the rule plan)
        DagPlan plan = buildPlan(application, memorySummary);
        log.info("Execution plan ({}, {}): {}", flowKit.dag() ? "dag" : "stages", plan.source(),
                plan.layers());

        // 3. Run the plan; reports are committed in plan order so the case log is deterministic
        LoanContext context = new LoanContext(application, flowKit);
        execute(plan, application, context);
        if (investigator != null) {
            context.addReport(investigator.investigate(application),
                    Provenance.of("application-owned-investigation", false, 0));
        }

        // 4. Policy envelope: the deterministic floor the LLM decision cannot go below
        PolicyEnvelope envelope = policy.evaluate(application, context.reports());
        if (envelope.complianceHold()) {
            return finishHold(merger.hold(envelope, application.applicantId(), context.reports()));
        }

        if (flowKit.challengeV2()) {
            return decideWithChallenges(application, context, memorySummary, envelope);
        }

        // 5. Decision + reflection loop, with policy invariants checked on every draft
        LoanDecision decision;
        try {
            decision = decisionAgent.decide(context, memorySummary);
        } catch (Exception e) {
            log.error("Decision agent failed ({}). Routing to manual review.", e.getMessage());
            return finish(unavailableDecision(application, context, "Decision agent unavailable"));
        }
        int revisions = 0;
        boolean unresolved = false;
        List<InvariantChecker.Violation> violations;
        while (true) {
            violations = invariants.check(decision, envelope);
            List<String> feedback = new java.util.ArrayList<>();
            violations.stream().filter(v -> v.kind() == InvariantChecker.Kind.BLOCKING)
                    .forEach(v -> feedback.add(v.message()));
            if (feedback.isEmpty()) {
                try {
                    CriticAgent.CritiqueReport critique = critic.critique(context, decision);
                    if (!critique.consistent()) {
                        feedback.addAll(critique.issues() == null ? List.of("Critic found inconsistencies")
                                : critique.issues());
                    }
                } catch (Exception e) {
                    // Fail safe: an unreviewed approval is not allowed to ship.
                    log.warn("Critic unavailable ({}).", e.getMessage());
                    if (decision.decision() == LoanDecision.Decision.APPROVED) {
                        decision = unavailableDecision(application, context, "Quality reviewer unavailable");
                        violations = List.of();
                    }
                    break;
                }
            }
            if (feedback.isEmpty()) {
                break;
            }
            if (revisions >= properties.getMaxRevisions()) {
                unresolved = true;
                break;
            }
            revisions++;
            log.info("[critic] revision {} requested: {}", revisions, feedback);
            try {
                decision = decisionAgent.decide(context, memorySummary, String.join("; ", feedback), revisions);
            } catch (Exception e) {
                log.error("Decision revision failed ({}). Routing to manual review.", e.getMessage());
                return finish(unavailableDecision(application, context, "Decision agent unavailable"));
            }
        }

        if (flowKit.v2() && context.state().injectionSuspected()) {
            // An agent output that looks like instructions is never allowed to ship an automatic outcome.
            log.warn("Injection-suspect finding in case; forcing manual review");
            metrics.recordCounter("flow.injection_suspect", "flow", "loan");
            unresolved = true;
        }

        LoanDecision merged = merger.merge(decision.withRevisionCount(revisions), envelope, violations,
                unresolved && !InvariantChecker.hasBlocking(violations),
                application.applicantId(), context.reports());
        return finish(merged);
    }

    /** WI-36: typed, bounded challenge protocol instead of the one-shot critic loop. */
    private LoanDecision decideWithChallenges(LoanApplication application, LoanContext context,
                                              String memorySummary, PolicyEnvelope firstEnvelope) {
        ChallengeCoordinator.OwnerRechecker rechecker = (agentId, claim) -> {
            LoanAgent agent = agentsByName.get(agentId);
            if (agent == null) {
                return java.util.Optional.empty();
            }
            LoanContext view = new LoanContext(application, flowKit);
            flowKit.specs().find(agentId).ifPresent(spec -> context.reports().stream()
                    .filter(r -> spec.reads().contains(r.agentName()))
                    .forEach(r -> view.addReport(r, Provenance.of("none", false, 0))));
            LoanAgent.Run run = agent.run(view.withChallenge(claim));
            context.revise(run.report(), run.provenance());
            return java.util.Optional.of(run.report());
        };
        ChallengeCoordinator.Outcome outcome = challenges.run(new ChallengeCoordinator.Request(
                context, memorySummary, () -> policy.evaluate(application, context.reports()), rechecker));
        metrics.recordCounter("challenge.outcome", "termination", outcome.termination().name());
        log.info("[challenge] {} after {} round(s){}", outcome.termination(), outcome.rounds(),
                outcome.reason() == null ? "" : ": " + outcome.reason());

        if (outcome.decision() == null) {
            return finish(unavailableDecision(application, context, outcome.reason()), outcome.evidence());
        }
        // The re-checks may have changed the reports, so the policy floor is recomputed from the final ones.
        PolicyEnvelope envelope = policy.evaluate(application, context.reports());
        if (envelope.complianceHold()) {
            return finishHold(merger.hold(envelope, application.applicantId(), context.reports()));
        }
        LoanDecision decision = outcome.decision();
        List<InvariantChecker.Violation> violations = new java.util.ArrayList<>();
        if (outcome.termination() == ChallengeCoordinator.Termination.UNAVAILABLE) {
            decision = unavailableDecision(application, context, outcome.reason());
        } else {
            violations.addAll(outcome.violations());
        }
        outcome.open().forEach(i -> violations.add(new InvariantChecker.Violation(
                InvariantChecker.Kind.CORRECTABLE, "CHALLENGE_OPEN:" + i.ruleId(), i.claim())));
        boolean needsHuman = outcome.needsHuman(flowKit.autonomyLevel());
        if (flowKit.v2() && context.state().injectionSuspected()) {
            log.warn("Injection-suspect finding in case; forcing manual review");
            metrics.recordCounter("flow.injection_suspect", "flow", "loan");
            needsHuman = true;
        }
        boolean blocking = InvariantChecker.hasBlocking(violations);
        LoanDecision merged = merger.merge(decision.withRevisionCount(outcome.rounds()), envelope, violations,
                needsHuman && !blocking, application.applicantId(), context.reports());
        return finish(merged, outcome.evidence());
    }

    /** Sanctions/AML route: full detail to the audit trail and compliance queue, nothing to the customer. */
    private LoanDecision finishHold(LoanDecision internal) {
        memory.record(internal);
        metrics.recordDecision(internal.decision().name());
        events.publish(io.github.chdurai.agenticbanking.ops.PlatformEvent.of("loan.compliance_hold", "loan", internal.applicationId(),
                "Restricted compliance route (" + String.join(", ", internal.internalReasonCodes()) + ")"));
        List<String> details = new java.util.ArrayList<>();
        details.add("Reason codes: " + String.join(", ", internal.internalReasonCodes()));
        internal.agentReports().forEach(r -> details.add("%s [%s, risk %d]: %s"
                .formatted(r.agentName(), r.status(), r.riskScore(), r.summary())));
        ApprovalEvidencePacket packet = loanEvidencePacket(internal, details, List.of(),
                "Compliance hold recommended by policy", true);
        approvals.submit("compliance", internal.applicationId(),
                "Compliance hold: restricted review required", details, packet);
        return internal.customerView();
    }

    /** Case memory (audit trail), telemetry, and the human review queue. */
    private LoanDecision finish(LoanDecision decision) {
        return finish(decision, List.of());
    }

    private LoanDecision finish(LoanDecision decision, List<String> extraEvidence) {
        memory.record(decision);
        metrics.recordDecision(decision.decision().name());
        events.publish(io.github.chdurai.agenticbanking.ops.PlatformEvent.of("loan.decided", "loan", decision.applicationId(),
                decision.decision().name() + " (revisions " + decision.revisionCount() + ")"));
        if (decision.decision() == LoanDecision.Decision.MANUAL_REVIEW) {
            List<String> details = new java.util.ArrayList<>();
            details.add("Reasoning: " + decision.reasoning());
            details.addAll(decision.conditions().stream().map(c -> "Verify: " + c).toList());
            details.addAll(extraEvidence);
            decision.agentReports().forEach(r -> details.add("%s [%s, risk %d]: %s"
                    .formatted(r.agentName(), r.status(), r.riskScore(), r.summary())));
            ApprovalEvidencePacket packet = loanEvidencePacket(decision, details, extraEvidence,
                    "Loan recommendation: " + decision.decision(), false);
            if (recoverySimulation) {
                packet = new ApprovalEvidencePacket(packet.schemaVersion(), packet.flow(), packet.subjectId(),
                        packet.minimizedCaseFacts(), packet.agentFindings(), packet.policyEnvelope(),
                        packet.invariantResults(), packet.criticResult(), packet.artifactSet(),
                        new ApprovalEvidencePacket.SideEffectPreview(
                                List.of("SIMULATED ONLY: record one durable booking receipt; no funds moved."),
                                List.of("No simulated booking.")),
                        packet.uncertaintySignals(), packet.aiRecommendation(), packet.maxRiskScore(), packet.nonTrivialRisk());
            }
            approvals.submit("loan", decision.applicationId(),
                    "Loan application requires underwriter review", details, packet,
                    recoverySimulation ? io.github.chdurai.agenticbanking.approval.SimulatedLoanBooking.action() : null, null, null);
        }
        if (decision.decision() == LoanDecision.Decision.DECLINED && !decision.customerReasonCodes().isEmpty()) {
            events.publish(io.github.chdurai.agenticbanking.ops.PlatformEvent.of("loan.adverse_action", "loan", decision.applicationId(),
                    "Adverse action notice due: " + String.join("; ", decision.customerReasonCodes())));
        }
        return decision.customerView();
    }

    private static ApprovalEvidencePacket loanEvidencePacket(LoanDecision decision,
                                                             List<String> details,
                                                             List<String> extraEvidence,
                                                             String recommendation,
                                                             boolean complianceFlow) {
        List<ApprovalEvidencePacket.AgentFinding> findings = decision.agentReports().stream()
                .map(r -> new ApprovalEvidencePacket.AgentFinding(
                        r.agentName(), r.status().name(), r.riskScore(), r.summary(), r.findings()))
                .toList();
        int maxRisk = decision.agentReports().stream().mapToInt(AgentReport::riskScore).max().orElse(0);
        List<String> uncertainty = decision.agentReports().stream()
                .filter(r -> r.status() == AgentReport.Status.UNAVAILABLE || r.status() == AgentReport.Status.WARN)
                .map(r -> r.agentName() + " status " + r.status())
                .toList();
        List<String> invariants = decision.conditions().stream().map(c -> "Condition: " + c).toList();
        List<String> policy = new java.util.ArrayList<>();
        if (decision.policyVersion() != null && !decision.policyVersion().isBlank()) {
            policy.add("policyVersion=" + decision.policyVersion());
        }
        if (!decision.internalReasonCodes().isEmpty()) {
            policy.add("internalReasonCodes=" + String.join(", ", decision.internalReasonCodes()));
        }
        if (!decision.customerReasonCodes().isEmpty()) {
            policy.add("customerReasonCodes=" + String.join(", ", decision.customerReasonCodes()));
        }
        String critic = extraEvidence.stream().filter(s -> s.startsWith("Critic:")).findFirst().orElse("n/a");
        List<String> artifactSet = decision.policyVersion() == null || decision.policyVersion().isBlank()
                ? List.of()
                : List.of("policy:" + decision.policyVersion());
        ApprovalEvidencePacket.SideEffectPreview sideEffects = new ApprovalEvidencePacket.SideEffectPreview(
                List.of(complianceFlow
                                ? "Route case to compliance investigation queue; no customer-facing notice."
                                : "Finalize decision and emit loan.final event.",
                        decision.decision() == LoanDecision.Decision.DECLINED
                                ? "Emit adverse-action notice event."
                                : "No irreversible side-effect in HITL step."),
                List.of("Keep case in manual review; no automated execution."));
        boolean nonTrivial = maxRisk >= 60
                || decision.decision() == LoanDecision.Decision.MANUAL_REVIEW
                || decision.decision() == LoanDecision.Decision.COMPLIANCE_HOLD
                || !uncertainty.isEmpty();
        return new ApprovalEvidencePacket(1, complianceFlow ? "compliance" : "loan", decision.applicationId(),
                List.of("applicationId=" + decision.applicationId(), "decision=" + decision.decision()),
                findings, policy, invariants, critic, artifactSet, sideEffects, uncertainty, recommendation,
                maxRisk, nonTrivial);
    }

    private LoanDecision unavailableDecision(LoanApplication application, LoanContext context, String why) {
        return new LoanDecision(application.applicantId(), LoanDecision.Decision.MANUAL_REVIEW,
                BigDecimal.ZERO, null, List.of("Underwriter must complete the review manually"), List.of(),
                why + "; routed to a human underwriter (fail-safe).", context.reports(), 0, Instant.now());
    }

    private DagPlan buildPlan(LoanApplication application, String memorySummary) {
        String caseId = application.applicantId();
        if (flowKit.dag()) {
            DagPlan plan;
            if (!properties.isPlannerEnabled()) {
                plan = planResolver.rules("loan");
            } else {
                try {
                    PlannerAgent.PlanOutput output = planner.plan(application, memorySummary);
                    plan = planResolver.resolve("loan", caseId, output.stages(), output.rationale(),
                            DagPlan.Source.LLM);
                } catch (Exception e) {
                    log.warn("Planner output unusable ({}). Using rule plan.", e.getMessage());
                    plan = planResolver.rules("loan");
                }
            }
            planResolver.announce(plan, caseId);
            return plan;
        }
        ExecutionPlan stages = legacyPlan(application, memorySummary);
        return DagPlan.barrier("loan", stages.stages(), stages.rationale(),
                stages.plannerGenerated() ? DagPlan.Source.LLM : DagPlan.Source.RULES);
    }

    private ExecutionPlan legacyPlan(LoanApplication application, String memorySummary) {
        if (!properties.isPlannerEnabled()) {
            return ExecutionPlan.defaultPlan();
        }
        try {
            PlannerAgent.PlanOutput output = planner.plan(application, memorySummary);
            return ExecutionPlan.validate(output.stages(), output.rationale(), agentsByName.keySet());
        } catch (Exception e) {
            log.warn("Planner output unusable ({}). Using fallback plan.", e.getMessage());
            return ExecutionPlan.defaultPlan();
        }
    }

    private record LoanResult(AgentReport report, Provenance provenance) {}

    private void execute(DagPlan plan, LoanApplication application, LoanContext context) {
        FlowRuntime.NodeExecutor<LoanResult> node = (agentId, inputs) -> {
            // Each agent sees only the findings of the nodes it waited for, so its prompt is deterministic.
            LoanContext view = new LoanContext(application, flowKit);
            inputs.values().forEach(in -> view.addReport(in.report(), in.provenance()));
            LoanAgent.Run run = agentsByName.get(agentId).run(view);
            return new LoanResult(run.report(), run.provenance());
        };
        // One failed agent must not sink the case: it is marked UNAVAILABLE so a human reviews.
        FlowRuntime.Degrader<LoanResult> degrade = (agentId, error, ms) -> {
            log.error("[{}] failed on primary and fallback model: {}", agentId, error.getMessage());
            return new LoanResult(AgentReport.unavailable(agentId, error.getClass().getSimpleName()),
                    Provenance.of("none", true, ms));
        };
        java.util.function.Predicate<LoanResult> hardFail = r ->
                r.report().failed() && r.report().riskScore() >= properties.getFailFastThreshold();
        FlowRuntime.DagRun<LoanResult> run = runtime.run(application.applicantId(), plan, node,
                new FlowRuntime.Options<>(hardFail, degrade, r -> r.report().status().name()));

        AgentReport firstHardFail = null;
        for (FlowRuntime.NodeResult<LoanResult> result : run.results()) {
            if (!result.hasValue()) {
                continue;
            }
            context.addReport(result.value().report(), result.value().provenance());
            if (firstHardFail == null && hardFail.test(result.value())) {
                firstHardFail = result.value().report();
            }
        }
        if (firstHardFail != null) {
            context.terminate(firstHardFail.agentName() + " hard-failed with riskScore " + firstHardFail.riskScore());
            log.warn("Fail-fast triggered by {} (riskScore={})", firstHardFail.agentName(),
                    firstHardFail.riskScore());
        } else if (run.stopped()) {
            context.terminate(run.stopReason());
        }
    }

    private LoanDecision blockedDecision(LoanApplication application, List<String> flags) {
        return new LoanDecision(application.applicantId(), LoanDecision.Decision.DECLINED,
                BigDecimal.ZERO, null, List.of(),
                List.of("Application rejected by input safety screening"),
                "Request blocked before agent processing. Flags: " + String.join("; ", flags),
                List.of(), 0, Instant.now());
    }
}
