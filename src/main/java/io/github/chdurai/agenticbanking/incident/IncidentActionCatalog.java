package io.github.chdurai.agenticbanking.incident;

import io.github.chdurai.agenticbanking.approval.Roles;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Allow-listed remediation actions. Unknown actions or invalid params are never executable.
 */
@Component
public class IncidentActionCatalog {

    public record ParamRule(int min, int max) {
        boolean valid(String value) {
            try {
                int v = Integer.parseInt(value);
                return v >= min && v <= max;
            } catch (RuntimeException e) {
                return false;
            }
        }
    }

    public record ActionDef(String id, RemediationPlan.RiskLevel baseRisk, String rollbackActionId,
                            String requiredRole, Map<String, ParamRule> params, List<String> aliases) {}

    public record Validation(boolean valid, ActionDef def, String actionId, Map<String, String> params, String reason) {}

    private final Map<String, ActionDef> defs = new LinkedHashMap<>();
    private final Map<String, String> aliasToId = new LinkedHashMap<>();

    public IncidentActionCatalog() {
        add(new ActionDef("kill_idle_sessions", RemediationPlan.RiskLevel.LOW, "none", Roles.INCIDENT_COMMANDER,
                Map.of("maxSessions", new ParamRule(1, 200)),
                List.of("kill idle sessions")));
        add(new ActionDef("restart_pod", RemediationPlan.RiskLevel.LOW, "restart_pod", Roles.INCIDENT_COMMANDER,
                Map.of("maxPods", new ParamRule(1, 3)),
                List.of("restart pod", "restart pods")));
        add(new ActionDef("clear_cache", RemediationPlan.RiskLevel.LOW, "none", Roles.INCIDENT_COMMANDER,
                Map.of(),
                List.of("clear cache")));
        add(new ActionDef("scale_replicas", RemediationPlan.RiskLevel.MEDIUM, "scale_replicas", Roles.INCIDENT_COMMANDER,
                Map.of("delta", new ParamRule(1, 5), "targetReplicas", new ParamRule(1, 50)),
                List.of("scale replicas")));
        add(new ActionDef("rollback_deploy", RemediationPlan.RiskLevel.HIGH, "rollback_deploy", Roles.INCIDENT_COMMANDER,
                Map.of("maxRevisions", new ParamRule(1, 5)),
                List.of("rollback deploy", "roll back deploy")));
        add(new ActionDef("failover_db", RemediationPlan.RiskLevel.HIGH, "none", Roles.INCIDENT_COMMANDER,
                Map.of(),
                List.of("failover db", "promote replica")));
        add(new ActionDef("rotate_credentials", RemediationPlan.RiskLevel.HIGH, "none", Roles.INCIDENT_COMMANDER,
                Map.of(),
                List.of("rotate credentials")));
    }

    private void add(ActionDef def) {
        defs.put(def.id(), def);
        aliasToId.put(normalize(def.id()), def.id());
        for (String alias : def.aliases()) {
            aliasToId.put(normalize(alias), def.id());
        }
    }

    public Optional<ActionDef> find(String action) {
        if (action == null || action.isBlank()) {
            return Optional.empty();
        }
        String id = aliasToId.getOrDefault(normalize(action), action);
        return Optional.ofNullable(defs.get(id));
    }

    public Validation validate(RemediationPlan.Step step) {
        if (step == null) {
            return new Validation(false, null, null, Map.of(), "null step");
        }
        Optional<ActionDef> found = find(step.action());
        if (found.isEmpty()) {
            return new Validation(false, null, step.action(), Map.of(), "unknown action: " + step.action());
        }
        ActionDef def = found.get();
        Map<String, String> params = step.params() == null ? Map.of() : step.params();

        for (String k : params.keySet()) {
            if (!def.params().containsKey(k)) {
                return new Validation(false, def, def.id(), params, "unknown param '" + k + "' for action " + def.id());
            }
        }
        for (Map.Entry<String, ParamRule> e : def.params().entrySet()) {
            String value = params.get(e.getKey());
            if (value == null) {
                return new Validation(false, def, def.id(), params, "missing param '" + e.getKey() + "'");
            }
            if (!e.getValue().valid(value)) {
                return new Validation(false, def, def.id(), params,
                        "out-of-bounds param '" + e.getKey() + "'=" + value);
            }
        }
        return new Validation(true, def, def.id(), Map.copyOf(params), null);
    }

    /** Prompt contract: action ids and strict parameter bounds. */
    public String promptContract() {
        StringBuilder sb = new StringBuilder("Action catalog (use action IDs exactly, with params map):\n");
        for (ActionDef d : defs.values()) {
            sb.append("- ").append(d.id()).append(" params=").append(d.params()).append('\n');
        }
        return sb.toString();
    }

    private static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
    }
}
