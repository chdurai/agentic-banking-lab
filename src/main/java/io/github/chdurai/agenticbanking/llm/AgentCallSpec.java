package io.github.chdurai.agenticbanking.llm;

import java.util.List;

/**
 * Everything the gateway needs for one structured call. The agent id is explicit metadata
 * (never inferred from prompt text) and drives routing, budgets, metrics and prompt version.
 *
 * @param system      the agent's baseline system prompt (the registry may override by version)
 * @param tools       optional @Tool objects; empty for evidence-injection agents
 * @param budget      shared per-case budget; null means unbudgeted
 * @param flow        loan | incident | grievance | document (for events and budgets)
 * @param subjectId   case/application id for audit correlation; may be null
 */
public record AgentCallSpec<T>(String agentId, String system, String user, Class<T> outputType,
                               double temperature, Integer maxTokens, List<Object> tools,
                               CaseBudget budget, String flow, String subjectId) {

    public AgentCallSpec {
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException("agentId is required");
        }
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    public static <T> AgentCallSpec<T> of(String agentId, String system, String user,
                                          Class<T> outputType, double temperature) {
        return new AgentCallSpec<>(agentId, system, user, outputType, temperature, null,
                List.of(), null, null, null);
    }

    public AgentCallSpec<T> withTools(Object... toolObjects) {
        return new AgentCallSpec<>(agentId, system, user, outputType, temperature, maxTokens,
                toolObjects == null ? List.of() : List.of(toolObjects), budget, flow, subjectId);
    }

    public AgentCallSpec<T> withBudget(CaseBudget caseBudget) {
        return new AgentCallSpec<>(agentId, system, user, outputType, temperature, maxTokens,
                tools, caseBudget, flow, subjectId);
    }

    public AgentCallSpec<T> forCase(String caseFlow, String caseSubjectId) {
        return new AgentCallSpec<>(agentId, system, user, outputType, temperature, maxTokens,
                tools, budget, caseFlow, caseSubjectId);
    }

    public AgentCallSpec<T> withMaxTokens(Integer limit) {
        return new AgentCallSpec<>(agentId, system, user, outputType, temperature, limit,
                tools, budget, flow, subjectId);
    }
}
