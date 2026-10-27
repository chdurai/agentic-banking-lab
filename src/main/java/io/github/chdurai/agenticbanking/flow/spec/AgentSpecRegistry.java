package io.github.chdurai.agenticbanking.flow.spec;

import io.github.chdurai.agenticbanking.flow.state.Finding;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Every agent's declared {@link AgentSpec}, validated once at construction so a bad spec fails the boot
 * (v3.1 §3.4): unique ids, {@code reads} that exist in the same flow and are plannable, no cycles,
 * and the mandatory agents of each flow present and marked required.
 */
public class AgentSpecRegistry {

    /** Plan invariants: no plan, override or planner output may remove these. */
    static final Map<String, Set<String>> REQUIRED_BY_FLOW = Map.of(
            "loan", Set.of("intake", "kyc", "compliance", "decision"),
            "incident", Set.of("incident-triage", "incident-diagnosis", "incident-remediation"),
            "grievance", Set.of("grievance-triage"));

    private final Map<String, AgentSpec> byId = new LinkedHashMap<>();

    public AgentSpecRegistry(Collection<AgentSpec> specs) {
        List<String> problems = new ArrayList<>();
        for (AgentSpec s : specs) {
            if (s.agentId() == null || s.agentId().isBlank()) {
                problems.add("spec with blank agentId");
            } else if (byId.putIfAbsent(s.agentId(), s) != null) {
                problems.add("duplicate spec for agent '" + s.agentId() + "'");
            }
        }
        validate(problems);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Agent spec validation failed: " + String.join("; ", problems));
        }
    }

    public static AgentSpecRegistry withDefaults() {
        return new AgentSpecRegistry(defaults());
    }

    public static AgentSpecRegistry withDefaults(Map<String, AgentSpecOverride> overrides) {
        Map<String, AgentSpec> merged = new LinkedHashMap<>();
        defaults().forEach(s -> merged.put(s.agentId(), s));
        for (Map.Entry<String, AgentSpecOverride> e : overrides.entrySet()) {
            AgentSpec base = merged.get(e.getKey());
            if (base == null) {
                throw new IllegalStateException("Agent spec validation failed: override for unknown agent '"
                        + e.getKey() + "' (known: " + new TreeSet<>(merged.keySet()) + ")");
            }
            merged.put(e.getKey(), e.getValue().apply(base));
        }
        return new AgentSpecRegistry(merged.values());
    }

    /** The built-in specs for the loan, incident and grievance flows. */
    public static List<AgentSpec> defaults() {
        return List.of(
                AgentSpec.of("loan", "intake").required(true).critical(true).build(),
                AgentSpec.of("loan", "kyc").reads("intake").mode(AgentSpec.Mode.HYBRID)
                        .required(true).critical(true).challengeable(1).build(),
                AgentSpec.of("loan", "credit-risk").reads("intake").mode(AgentSpec.Mode.HYBRID)
                        .critical(true).challengeable(1).build(),
                AgentSpec.of("loan", "compliance").reads("intake", "kyc", "credit-risk")
                        .mode(AgentSpec.Mode.HYBRID).required(true).critical(true).challengeable(1).build(),
                AgentSpec.of("loan", "decision").reads("intake", "kyc", "credit-risk", "compliance")
                        .writes(Set.of(Finding.Kind.DECISION_CANDIDATE)).onFailure(AgentSpec.OnFailure.HOLD)
                        .required(true).critical(true).challengeable(1).planned(false).build(),

                AgentSpec.of("incident", "incident-triage").onFailure(AgentSpec.OnFailure.ABORT)
                        .required(true).critical(true).build(),
                AgentSpec.of("incident", "incident-diagnosis").reads("incident-triage")
                        .mode(AgentSpec.Mode.HYBRID).onFailure(AgentSpec.OnFailure.ABORT)
                        .required(true).critical(true).build(),
                AgentSpec.of("incident", "incident-remediation").reads("incident-triage", "incident-diagnosis")
                        .mode(AgentSpec.Mode.HYBRID).onFailure(AgentSpec.OnFailure.ABORT)
                        .required(true).critical(true).build(),

                AgentSpec.of("grievance", "grievance-triage").onFailure(AgentSpec.OnFailure.HOLD)
                        .required(true).critical(true).build(),
                AgentSpec.of("grievance", "grievance-response").reads("grievance-triage")
                        .mode(AgentSpec.Mode.HYBRID).build());
    }

    private void validate(List<String> problems) {
        for (AgentSpec s : byId.values()) {
            String who = s.agentId();
            if (s.flow() == null || s.flow().isBlank()) problems.add(who + ": flow is required");
            if (s.version() < 1) problems.add(who + ": version must be >= 1");
            if (s.deadlineMs() <= 0) problems.add(who + ": deadlineMs must be positive");
            if (s.challengeable() < 0) problems.add(who + ": challengeable must be >= 0");
            if (s.maxTokens() < 0 || s.maxToolCalls() < 0) problems.add(who + ": budgets must not be negative");
            if (s.required() && s.mode() == null) problems.add(who + ": mode is required");
            for (String read : s.reads()) {
                AgentSpec source = byId.get(read);
                if (read.equals(who)) {
                    problems.add(who + ": reads itself");
                } else if (source == null) {
                    problems.add(who + ": reads unknown agent '" + read + "'");
                } else if (!source.flow().equals(s.flow())) {
                    problems.add(who + ": reads '" + read + "' from another flow (" + source.flow() + ")");
                } else if (!source.planned()) {
                    problems.add(who + ": reads '" + read + "' which runs outside the plan");
                }
            }
        }
        Set<String> flows = new HashSet<>();
        byId.values().forEach(s -> flows.add(s.flow()));
        for (String flow : flows) {
            Set<String> mandatory = REQUIRED_BY_FLOW.get(flow);
            if (mandatory == null) continue;
            for (String agent : mandatory) {
                AgentSpec s = byId.get(agent);
                if (s == null || !s.flow().equals(flow)) {
                    problems.add("flow '" + flow + "' is missing required agent '" + agent + "'");
                } else if (!s.required()) {
                    problems.add(agent + ": must be marked required (plan invariant of flow '" + flow + "')");
                }
            }
        }
        detectCycles(problems);
    }

    private void detectCycles(List<String> problems) {
        Map<String, Integer> color = new HashMap<>();
        for (String id : byId.keySet()) {
            visit(id, color, new ArrayList<>(), problems);
        }
    }

    private void visit(String id, Map<String, Integer> color, List<String> path, List<String> problems) {
        Integer c = color.get(id);
        if (c != null && c == 2) return;
        if (c != null && c == 1) {
            List<String> cycle = new ArrayList<>(path.subList(path.indexOf(id), path.size()));
            cycle.add(id);
            String msg = "reads cycle: " + String.join(" -> ", cycle);
            if (!problems.contains(msg)) problems.add(msg);
            return;
        }
        color.put(id, 1);
        path.add(id);
        AgentSpec s = byId.get(id);
        if (s != null) {
            for (String read : s.reads()) {
                if (byId.containsKey(read)) visit(read, color, path, problems);
            }
        }
        path.removeLast();
        color.put(id, 2);
    }

    public Optional<AgentSpec> find(String agentId) {
        return Optional.ofNullable(byId.get(agentId));
    }

    public AgentSpec require(String agentId) {
        return find(agentId).orElseThrow(() -> new IllegalArgumentException("no AgentSpec for '" + agentId + "'"));
    }

    /** Specs of one flow in declaration order. */
    public List<AgentSpec> forFlow(String flow) {
        return byId.values().stream().filter(s -> s.flow().equals(flow)).toList();
    }

    /** Specs of one flow that run as DAG nodes. */
    public List<AgentSpec> planned(String flow) {
        return forFlow(flow).stream().filter(AgentSpec::planned).toList();
    }

    public List<AgentSpec> all() {
        return List.copyOf(byId.values());
    }

    /** Agents {@code agentId} may see; empty when it has no spec or reads nothing. */
    public List<String> reads(String agentId) {
        return find(agentId).map(AgentSpec::reads).orElse(List.of());
    }
}
