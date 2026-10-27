package io.github.chdurai.agenticbanking.flow;

import io.github.chdurai.agenticbanking.flow.plan.DagPlan;

import java.time.Instant;
import java.util.List;

/** Write-ahead evidence, not an automatic replay engine. An interrupted attempt may have called a tool. */
public interface FlowExecutionJournal {
    record Owner(String runId, String tenantId, String token) {}
    record RunView(String runId, String flow, String status, int schemaVersion, String planSha256,
                   Instant startedAt, Instant finishedAt) {}
    record AttemptView(String nodeId, String status, String resultSha256, String failureCategory,
                       Long durationMs, Instant startedAt, Instant finishedAt) {}

    Owner begin(DagPlan plan, String caseId);
    void started(Owner owner, String node, List<String> predecessors);
    void completed(Owner owner, FlowRuntime.NodeResult<?> result);
    void finished(Owner owner, String status);
    List<RunView> runs(String caseId, int limit);
    List<AttemptView> attempts(String runId);
}
