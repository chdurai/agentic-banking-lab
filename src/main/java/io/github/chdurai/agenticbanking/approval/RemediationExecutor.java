package io.github.chdurai.agenticbanking.approval;

import io.github.chdurai.agenticbanking.incident.RemediationPlan;

import java.util.List;

/**
 * Executes remediation steps. The platform ships only the simulated implementation:
 * wiring a real executor (Kubernetes API, Azure Automation, ServiceNow change tasks)
 * is an integration decision that sits behind this interface and the approval queue.
 */
public interface RemediationExecutor {

    /** Returns one execution-log line per step. */
    List<String> execute(String incidentId, List<RemediationPlan.Step> steps);
}
