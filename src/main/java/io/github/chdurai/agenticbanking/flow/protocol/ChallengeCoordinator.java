package io.github.chdurai.agenticbanking.flow.protocol;

import io.github.chdurai.agenticbanking.agents.DecisionAgent;
import io.github.chdurai.agenticbanking.flow.FlowProperties;
import io.github.chdurai.agenticbanking.flow.spec.AgentSpecRegistry;
import io.github.chdurai.agenticbanking.flow.state.AgentMessage;
import io.github.chdurai.agenticbanking.flow.state.CaseState;
import io.github.chdurai.agenticbanking.model.AgentReport;
import io.github.chdurai.agenticbanking.model.LoanContext;
import io.github.chdurai.agenticbanking.model.LoanDecision;
import io.github.chdurai.agenticbanking.policy.InvariantChecker;
import io.github.chdurai.agenticbanking.policy.PolicyEnvelope;
import io.github.chdurai.agenticbanking.reflection.CriticAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Bounded challenge protocol (v3.1 §3.7) that replaces the one-shot critic loop.
 *
 * <pre>
 * candidate → detector + invariants (code) → [critic, residual only] → open BLOCKING issues?
 *     none → RESOLVED
 *     else → route: owner agent re-check (if challengeable, once per finding) / decision redraft
 *            → re-validate EVERY new candidate → loop (≤ max-rounds, each round must shrink the open set)
 * </pre>
 * Silent acceptance of an open BLOCKING issue is impossible: the only exits are RESOLVED (all BLOCKING issues
 * confirmed gone by code or critic), EXHAUSTED, ESCALATED and UNAVAILABLE, and the last three always reach a
 * human. A rebuttal never closes a BLOCKING issue: only the detector confirming it is gone does.
 */
@Component
public class ChallengeCoordinator {

    private static final Logger log = LoggerFactory.getLogger(ChallengeCoordinator.class);
    private static final String COORDINATOR = "challenge-coordinator";

    public enum Termination { RESOLVED, EXHAUSTED, ESCALATED, UNAVAILABLE }

    /** Re-runs the owning agent with the challenge attached; empty when the agent cannot be re-run. */
    @FunctionalInterface
    public interface OwnerRechecker {
        Optional<AgentReport> recheck(String agentId, String claim);
    }

    /** @param envelope re-evaluated every round because an owner re-check can change the reports */
    public record Request(LoanContext context, String memorySummary, Supplier<PolicyEnvelope> envelope,
                          OwnerRechecker rechecker) {}

    /**
     * @param decision   null only for UNAVAILABLE when no usable candidate exists
     * @param violations remaining invariant violations (the policy merge repairs or enforces them)
     * @param open       BLOCKING issues still open
     * @param advisory   ADVISORY issues raised (never auto-fixed, always shown to the reviewer)
     * @param rebutted   unchanged, missing or evidence-dropping owner responses requiring human review
     * @param reason     why the protocol stopped early; null when RESOLVED
     */
    public record Outcome(Termination termination, LoanDecision decision,
                          List<InvariantChecker.Violation> violations, List<ChallengeIssue> open,
                          List<ChallengeIssue> advisory, List<ChallengeIssue> rebutted, int rounds,
                          String reason) {

        /** L0/L1: anything unresolved or rebutted needs a person; L2+: only BLOCKING items do. */
        public boolean needsHuman(int autonomyLevel) {
            if (termination != Termination.RESOLVED || !open.isEmpty() || !rebutted.isEmpty()) {
                return true;
            }
            return autonomyLevel <= 1 && !advisory.isEmpty();
        }

        /** Lines for the reviewer's evidence packet. */
        public List<String> evidence() {
            List<String> lines = new ArrayList<>();
            if (termination != Termination.RESOLVED) {
                lines.add("Challenge protocol ended " + termination + (reason == null ? "" : ": " + reason));
            }
            open.forEach(i -> lines.add("Open challenge [" + i.ruleId() + "] on " + i.targetAgent() + ": " + i.claim()));
            rebutted.forEach(i -> lines.add("Rebutted challenge [" + i.ruleId() + "] on " + i.targetAgent()
                    + ": " + i.claim()));
            advisory.forEach(i -> lines.add("Advisory [" + i.ruleId() + "]: " + i.claim()));
            return lines;
        }
    }

    private final DecisionAgent decisionAgent;
    private final CriticAgent critic;
    private final ContradictionDetector detector;
    private final InvariantChecker invariants;
    private final AgentSpecRegistry specs;
    private final FlowProperties props;

    public ChallengeCoordinator(DecisionAgent decisionAgent, CriticAgent critic, ContradictionDetector detector,
                                InvariantChecker invariants, AgentSpecRegistry specs, FlowProperties props) {
        this.decisionAgent = decisionAgent;
        this.critic = critic;
        this.detector = detector;
        this.invariants = invariants;
        this.specs = specs;
        this.props = props;
    }

    public Outcome run(Request req) {
        LoanContext ctx = req.context();
        CaseState state = ctx.state();
        LoanDecision candidate;
        try {
            candidate = decisionAgent.decide(ctx, req.memorySummary());
        } catch (Exception e) {
            log.error("Decision agent failed ({}). Routing to manual review.", e.getMessage());
            return unavailable(null, "Decision agent unavailable", List.of(), 0);
        }

        Map<String, Integer> challengesPerOwner = new HashMap<>();
        List<ChallengeIssue> rebutted = new ArrayList<>();
        List<ChallengeIssue> previousOpen = null;
        int round = 0;
        while (true) {
            PolicyEnvelope envelope = req.envelope().get();
            List<InvariantChecker.Violation> violations = invariants.check(candidate, envelope);
            List<ChallengeIssue> issues = new ArrayList<>();
            issues.addAll(detector.detect(candidate, ctx.reports(), state.currentView()));
            violations.stream().filter(v -> v.kind() == InvariantChecker.Kind.BLOCKING)
                    .forEach(v -> issues.add(new ChallengeIssue(v.code(), ChallengeIssue.Severity.BLOCKING,
                            ChallengeIssue.DECISION, v.message(), ChallengeIssue.Source.INVARIANT)));

            if (issues.stream().noneMatch(ChallengeIssue::blocking)) {
                try {
                    CriticAgent.CritiqueReport critique = critic.critique(ctx, candidate);
                    if (!critique.consistent()) {
                        List<String> concerns = critique.issues() == null || critique.issues().isEmpty()
                                ? List.of("Critic found inconsistencies") : critique.issues();
                        concerns.forEach(c -> issues.add(new ChallengeIssue("CRITIC", ChallengeIssue.Severity.BLOCKING,
                                ChallengeIssue.DECISION, c, ChallengeIssue.Source.CRITIC)));
                    }
                } catch (Exception e) {
                    // Fail safe: an unreviewed approval is not allowed to ship.
                    log.warn("Critic unavailable ({}).", e.getMessage());
                    if (candidate.decision() == LoanDecision.Decision.APPROVED) {
                        return unavailable(candidate, "Quality reviewer unavailable", rebutted, round);
                    }
                    return outcome(Termination.RESOLVED, candidate, violations, List.of(), advisory(issues), rebutted,
                            round, null);
                }
            }

            List<ChallengeIssue> open = issues.stream().filter(ChallengeIssue::blocking).toList();
            List<ChallengeIssue> advisory = advisory(issues);
            if (open.isEmpty()) {
                return outcome(Termination.RESOLVED, candidate, violations, List.of(), advisory, rebutted, round, null);
            }
            if (round >= props.getMaxRounds()) {
                return outcome(Termination.EXHAUSTED, candidate, violations, open, advisory, rebutted, round,
                        "revision budget (" + props.getMaxRounds() + " rounds) spent");
            }
            if (previousOpen != null && open.size() >= previousOpen.size()) {
                return outcome(Termination.EXHAUSTED, candidate, violations, open, advisory, rebutted, round,
                        "round " + round + " did not reduce open BLOCKING issues (" + previousOpen.size()
                                + " -> " + open.size() + ")");
            }
            previousOpen = open;

            // Owner-directed challenges first: they may change the evidence the decision is redrafted on.
            boolean unroutable = false;
            Map<String, List<ChallengeIssue>> byOwner = new LinkedHashMap<>();
            open.stream().filter(issue -> !issue.aimedAtDecision()).forEach(issue ->
                    byOwner.computeIfAbsent(issue.targetAgent(), ignored -> new ArrayList<>()).add(issue));
            for (var entry : byOwner.entrySet()) {
                String owner = entry.getKey();
                List<ChallengeIssue> ownerIssues = entry.getValue();
                int sent = challengesPerOwner.getOrDefault(owner, 0);
                boolean challengeable = specs.find(owner).map(s -> s.challengeable() > 0).orElse(false)
                        && req.rechecker() != null;
                if (!challengeable || sent >= props.getMaxChallengesPerFinding()) {
                    unroutable = true;
                    continue;
                }
                challengesPerOwner.merge(owner, 1, Integer::sum);
                ownerIssues.forEach(issue -> post(state, AgentMessage.Type.CHALLENGE, COORDINATOR, owner, issue));
                Optional<AgentReport> before = ctx.reports().stream()
                        .filter(r -> r.agentName().equals(owner)).findFirst();
                Map<String, Object> beforeFacts = currentFacts(state, owner);
                String claim = String.join("; ", ownerIssues.stream()
                        .map(issue -> "[" + issue.ruleId() + "] " + issue.claim()).toList());
                Optional<AgentReport> after = recheck(req, owner, claim);
                Map<String, Object> afterFacts = currentFacts(state, owner);
                ownerIssues.forEach(issue -> post(state, AgentMessage.Type.CHALLENGE_RESPONSE, owner, COORDINATOR, issue));
                boolean evidenceDropped = !afterFacts.keySet().containsAll(beforeFacts.keySet());
                if (after.isEmpty() || evidenceDropped
                        || before.isPresent() && same(before.get(), after.get()) && beforeFacts.equals(afterFacts)) {
                    rebutted.addAll(ownerIssues);
                }
            }
            if (unroutable) {
                return outcome(Termination.ESCALATED, candidate, violations, open, advisory, rebutted, round,
                        "a BLOCKING issue targets an agent that cannot be challenged again");
            }

            round++;
            String feedback = String.join("; ", open.stream().map(i -> "[" + i.ruleId() + "] " + i.claim()).toList());
            for (ChallengeIssue issue : open) {
                if (issue.aimedAtDecision()) {
                    post(state, AgentMessage.Type.CHALLENGE, COORDINATOR, ChallengeIssue.DECISION, issue);
                }
            }
            try {
                candidate = decisionAgent.decide(ctx, req.memorySummary(), feedback, round);
            } catch (Exception e) {
                log.error("Decision revision failed ({}). Routing to manual review.", e.getMessage());
                return unavailable(null, "Decision agent unavailable", rebutted, round);
            }
            log.info("[challenge] round {} redraft requested: {}", round, feedback);
        }
    }

    private Optional<AgentReport> recheck(Request req, String owner, String claim) {
        try {
            return req.rechecker().recheck(owner, claim);
        } catch (Exception e) {
            log.warn("[challenge] {} re-check failed: {}", owner, e.getMessage());
            return Optional.empty();
        }
    }

    private static boolean same(AgentReport a, AgentReport b) {
        return a.status() == b.status() && a.riskScore() == b.riskScore();
    }

    private static Map<String, Object> currentFacts(CaseState state, String agentId) {
        Map<String, Object> facts = new LinkedHashMap<>();
        state.currentView().stream().filter(f -> f.agentId().equals(agentId))
                .forEach(f -> f.facts().forEach((key, value) -> facts.put(f.kind().name() + "/" + key, value)));
        return facts;
    }

    private static List<ChallengeIssue> advisory(List<ChallengeIssue> issues) {
        return issues.stream().filter(i -> !i.blocking()).toList();
    }

    private static Outcome outcome(Termination t, LoanDecision d, List<InvariantChecker.Violation> v,
                                   List<ChallengeIssue> open, List<ChallengeIssue> advisory,
                                   List<ChallengeIssue> rebutted, int rounds, String reason) {
        return new Outcome(t, d, v, open, advisory, List.copyOf(rebutted), rounds, reason);
    }

    private static Outcome unavailable(LoanDecision d, String reason, List<ChallengeIssue> rebutted, int rounds) {
        return new Outcome(Termination.UNAVAILABLE, d, List.of(), List.of(), List.of(), List.copyOf(rebutted),
                rounds, reason);
    }

    private static void post(CaseState state, AgentMessage.Type type, String from, String to, ChallengeIssue issue) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ruleId", issue.ruleId());
        payload.put("severity", issue.severity().name());
        payload.put("claim", issue.claim());
        state.post(new AgentMessage(null, null, null, 0, from, to, type, List.of(), payload, null, 1));
    }
}
