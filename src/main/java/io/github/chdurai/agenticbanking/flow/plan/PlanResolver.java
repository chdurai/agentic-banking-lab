package io.github.chdurai.agenticbanking.flow.plan;

import io.github.chdurai.agenticbanking.flow.FlowProperties;
import io.github.chdurai.agenticbanking.flow.spec.AgentSpec;
import io.github.chdurai.agenticbanking.flow.spec.AgentSpecRegistry;
import io.github.chdurai.agenticbanking.ops.PlatformEvent;
import io.github.chdurai.agenticbanking.ops.PlatformEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a plan proposal (from rules or the LLM planner) into a validated {@link DagPlan} (v3.1 §3.5).
 * The LLM only proposes an ordering; this class owns the invariants: known agents, no duplicates, every
 * required agent present, every {@code reads} dependency scheduled strictly earlier, and node / LLM-call
 * caps. Any violation falls back to the rule plan and is reported, so a hostile or confused planner can
 * degrade performance but never safety.
 */
@Component
public class PlanResolver {

    private static final Logger log = LoggerFactory.getLogger(PlanResolver.class);

    private final AgentSpecRegistry specs;
    private final FlowProperties props;
    private final PlatformEventPublisher events;

    public PlanResolver(AgentSpecRegistry specs, FlowProperties props, PlatformEventPublisher events) {
        this.specs = specs;
        this.props = props;
        this.events = events;
    }

    /** The deterministic plan: every planned agent of the flow, edges derived from {@code reads}. */
    public DagPlan rules(String flow) {
        List<AgentSpec> planned = specs.planned(flow);
        Set<String> ids = new LinkedHashSet<>();
        planned.forEach(s -> ids.add(s.agentId()));
        List<String> order = topological(planned, ids);
        return new DagPlan(flow, order, edges(order, ids), "rule-based plan", 1, DagPlan.Source.RULES, null);
    }

    /**
     * Validates a staged proposal. On any violation returns the rule plan with {@code fallbackReason} set
     * and publishes {@code plan.fallback}.
     */
    public DagPlan resolve(String flow, String caseId, List<List<String>> stages, String rationale,
                           DagPlan.Source source) {
        try {
            return fromStages(flow, stages, rationale, source);
        } catch (IllegalArgumentException e) {
            log.warn("[{}] {} plan rejected ({}); using rule plan", flow, source, e.getMessage());
            DagPlan rules = rules(flow);
            DagPlan fallback = new DagPlan(flow, rules.nodes(), rules.edges(), rules.rationale(), 1,
                    DagPlan.Source.RULES, e.getMessage());
            events.publish(PlatformEvent.of("plan.fallback", flow, caseId, e.getMessage()));
            return fallback;
        }
    }

    /** Announces the plan a DAG-mode case will run (quiet in legacy mode). */
    public void announce(DagPlan plan, String caseId) {
        events.publish(PlatformEvent.of("plan.resolved", plan.flow(), caseId, plan.describe()));
    }

    /** @throws IllegalArgumentException with the first violated invariant */
    public DagPlan fromStages(String flow, List<List<String>> stages, String rationale, DagPlan.Source source) {
        if (stages == null || stages.isEmpty()) {
            throw new IllegalArgumentException("empty plan");
        }
        Map<String, Integer> stageOf = new LinkedHashMap<>();
        for (int i = 0; i < stages.size(); i++) {
            List<String> stage = stages.get(i);
            if (stage == null) {
                continue;
            }
            for (String raw : stage) {
                String id = raw == null ? "" : raw.trim().toLowerCase();
                AgentSpec spec = specs.find(id).orElse(null);
                if (spec == null || !spec.flow().equals(flow) || !spec.planned()) {
                    throw new IllegalArgumentException("unknown agent in plan: " + id);
                }
                if (stageOf.putIfAbsent(id, i) != null) {
                    throw new IllegalArgumentException("duplicate agent in plan: " + id);
                }
            }
        }
        List<AgentSpec> planned = specs.planned(flow);
        for (AgentSpec s : planned) {
            if (s.required() && !stageOf.containsKey(s.agentId())) {
                throw new IllegalArgumentException("plan must include " + s.agentId());
            }
        }
        if (stageOf.size() > props.getMaxPlanNodes()) {
            throw new IllegalArgumentException("plan has " + stageOf.size() + " nodes (max "
                    + props.getMaxPlanNodes() + ")");
        }
        long llm = stageOf.keySet().stream().filter(id -> specs.require(id).usesLlm()).count();
        if (llm > props.getMaxLlmCalls()) {
            throw new IllegalArgumentException("plan needs " + llm + " LLM calls (max " + props.getMaxLlmCalls() + ")");
        }
        for (String id : stageOf.keySet()) {
            for (String read : specs.require(id).reads()) {
                Integer readStage = stageOf.get(read);
                if (readStage != null && readStage >= stageOf.get(id)) {
                    throw new IllegalArgumentException(id + " reads " + read + " but is scheduled "
                            + (readStage.equals(stageOf.get(id)) ? "alongside" : "before") + " it");
                }
            }
        }
        List<String> order = new ArrayList<>(stageOf.keySet());
        order.sort((a, b) -> Integer.compare(stageOf.get(a), stageOf.get(b)));
        if (source == DagPlan.Source.LLM) {
            // A planner's stage order is an explicit sequencing instruction (e.g. "KYC first so a sanctions
            // hit saves the credit-risk call"), so it is kept as barriers on top of the reads edges.
            return DagPlan.barrier(flow, stages(order, stageOf), rationale, source);
        }
        return new DagPlan(flow, order, edges(order, stageOf.keySet()), rationale, 1, source, null);
    }

    private static List<List<String>> stages(List<String> order, Map<String, Integer> stageOf) {
        Map<Integer, List<String>> byStage = new java.util.TreeMap<>();
        order.forEach(id -> byStage.computeIfAbsent(stageOf.get(id), k -> new ArrayList<>()).add(id));
        return List.copyOf(byStage.values());
    }

    private List<DagPlan.Edge> edges(List<String> order, Set<String> inPlan) {
        List<DagPlan.Edge> edges = new ArrayList<>();
        for (String id : order) {
            for (String read : specs.require(id).reads()) {
                if (inPlan.contains(read)) {
                    edges.add(new DagPlan.Edge(read, id));
                }
            }
        }
        return edges;
    }

    private List<String> topological(List<AgentSpec> planned, Set<String> ids) {
        List<String> order = new ArrayList<>();
        Set<String> placed = new LinkedHashSet<>();
        while (order.size() < planned.size()) {
            boolean progressed = false;
            for (AgentSpec s : planned) {
                if (placed.contains(s.agentId())) {
                    continue;
                }
                if (s.reads().stream().filter(ids::contains).allMatch(placed::contains)) {
                    order.add(s.agentId());
                    placed.add(s.agentId());
                    progressed = true;
                }
            }
            if (!progressed) {
                throw new IllegalStateException("cycle among planned agents");
            }
        }
        return order;
    }
}
