package io.github.chdurai.agenticbanking.mcp;

import io.github.chdurai.agenticbanking.approval.ApprovalRequest;
import io.github.chdurai.agenticbanking.approval.ApprovalQueue;
import io.github.chdurai.agenticbanking.knowledge.KnowledgeGraph;
import io.github.chdurai.agenticbanking.ops.AgentMetrics;
import io.github.chdurai.agenticbanking.rag.GrievancePlaybookRetriever;
import io.github.chdurai.agenticbanking.rag.PolicyRetriever;
import io.github.chdurai.agenticbanking.rag.RunbookRetriever;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Read-only platform capabilities exposed over MCP so external agents and IDEs can reuse them.
 * Deliberately excludes anything that mutates state or exposes case content (grievances,
 * loan applications, approval decisions).
 */
@Component
public class PlatformMcpTools {
    @org.springframework.beans.factory.annotation.Autowired
    private io.github.chdurai.agenticbanking.security.CallerProperties callers;

    private final PolicyRetriever policies;
    private final RunbookRetriever runbooks;
    private final GrievancePlaybookRetriever playbooks;
    private final KnowledgeGraph graph;
    private final AgentMetrics metrics;
    private final ApprovalQueue approvals;

    public PlatformMcpTools(PolicyRetriever policies, RunbookRetriever runbooks,
                            GrievancePlaybookRetriever playbooks, KnowledgeGraph graph,
                            AgentMetrics metrics, ApprovalQueue approvals) {
        this.policies = policies;
        this.runbooks = runbooks;
        this.playbooks = playbooks;
        this.graph = graph;
        this.metrics = metrics;
        this.approvals = approvals;
    }

    @Tool(description = "Search lending policies, incident runbooks and grievance playbooks. "
            + "Returns cited excerpts grouped by corpus.")
    public Map<String, List<String>> searchKnowledge(
            @ToolParam(description = "Natural-language query") String query,
            @ToolParam(description = "Max excerpts per corpus (1-5)", required = false) Integer topK) {
        requireRole("KNOWLEDGE_READER");
        if (query == null || query.isBlank() || query.length() > 500) throw new IllegalArgumentException("Invalid query");
        int k = topK == null ? 3 : Math.max(1, Math.min(5, topK));
        return Map.of("policies", policies.retrieve(query, k),
                "runbooks", runbooks.retrieve(query, k),
                "grievancePlaybooks", playbooks.retrieve(query, k));
    }

    @Tool(description = "Knowledge-graph view of a service or component: owner, dependencies, "
            + "blast radius (what breaks if it fails) and linked runbooks.")
    public Map<String, Object> getServiceGraph(
            @ToolParam(description = "Service or component id or name, e.g. loan-db") String entity) {
        requireRole("KNOWLEDGE_READER");
        if (entity == null || entity.isBlank() || entity.length() > 200) throw new IllegalArgumentException("Invalid entity");
        Map<String, Object> view = graph.view(entity);
        return view.isEmpty() ? Map.of("error", "unknown entity: " + entity) : view;
    }

    @Tool(description = "LLM usage, estimated cost and latency per agent and model (FinOps report).")
    public Map<String, Object> getCostReport() {
        requireRole(io.github.chdurai.agenticbanking.approval.Roles.PLATFORM_ADMIN);
        return metrics.costReport();
    }

    @Tool(description = "Counts of human-approval requests waiting for a decision, by flow. No case content.")
    public Map<String, Long> getPendingApprovalCounts() {
        requireRole("APPROVAL_READER");
        return approvals.list(ApprovalRequest.Status.PENDING).stream()
                .filter(r -> io.github.chdurai.agenticbanking.security.CallerContext.current().roles().contains(r.requiredRole())
                        || io.github.chdurai.agenticbanking.security.CallerContext.current().roles().contains(io.github.chdurai.agenticbanking.approval.Roles.PLATFORM_ADMIN))
                .collect(java.util.stream.Collectors.groupingBy(ApprovalRequest::flow,
                        java.util.stream.Collectors.counting()));
    }

    private void requireRole(String role) {
        if (callers != null && (callers.required() || !callers.credentials().isEmpty())
                && !io.github.chdurai.agenticbanking.security.CallerContext.isBound()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNAUTHORIZED, "Missing MCP caller scope");
        }
        var roles = io.github.chdurai.agenticbanking.security.CallerContext.current().roles();
        if (!roles.contains(role) && !roles.contains(io.github.chdurai.agenticbanking.approval.Roles.PLATFORM_ADMIN)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "MCP tool role required");
        }
    }
}
