package io.github.chdurai.agenticbanking.incident;

import io.github.chdurai.agenticbanking.llm.AgentCallSpec;
import io.github.chdurai.agenticbanking.llm.LlmGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Incident agent 3: produces an ordered remediation plan with per-step risk
 * classification. Conservative by design: anything destructive, security-related,
 * or irreversible must be marked requiresApproval=true.
 */
@Component
public class IncidentRemediationAgent {

    private static final Logger log = LoggerFactory.getLogger(IncidentRemediationAgent.class);
    static final String NAME = "incident-remediation";

    private static final String SYSTEM = """
            You are an incident remediation planner. Given the triage and diagnosis, produce
            an ordered remediation plan. Rules:
            - Steps execute in order; each has action (catalog id), params (string map),
              riskLevel (LOW/MEDIUM/HIGH), and requiresApproval.
            - Do NOT emit shell commands or free-text executable instructions.
            - LOW risk + requiresApproval=false ONLY for read-only or fully reversible actions
              (restart pod, clear cache, scale replicas, kill idle sessions).
            - Anything touching data, credentials, security controls, or production config =>
              requiresApproval=true. Rollbacks of deployments => requiresApproval=true.
            - SEV1/SEV2: first step must always be "assign incident commander and notify
              stakeholders" with requiresApproval=true.
            - Include estimatedMinutes total and a rollbackPlan.
            """;

    private final io.github.chdurai.agenticbanking.flow.FlowKit flow;
    private final IncidentActionCatalog catalog;
    private final LlmGateway llm;

    public IncidentRemediationAgent(LlmGateway llm, io.github.chdurai.agenticbanking.flow.FlowKit flow, IncidentActionCatalog catalog,
                                    io.github.chdurai.agenticbanking.capability.CapabilityGuard capabilities) {
        this.llm = llm;
        this.flow = flow;
        this.catalog = catalog;
        this.capabilities = capabilities;
    }

    private final io.github.chdurai.agenticbanking.capability.CapabilityGuard capabilities;

    private String system() {
        return flow.v2() ? SYSTEM + "\n" + io.github.chdurai.agenticbanking.guardrails.Spotlight.INSTRUCTION : SYSTEM;
    }

    private String fence(String text) {
        return flow.v2() ? flow.prompts().untrusted(text) : text;
    }

    public RemediationPlan plan(Incident incident, IncidentTriage triage, DiagnosisReport diagnosis) {
        try (var scope = capabilities.enter(NAME, incident.incidentId())) {
            return planScoped(incident, triage, diagnosis);
        }
    }

    private RemediationPlan planScoped(Incident incident, IncidentTriage triage, DiagnosisReport diagnosis) {
        capabilities.checkView("incident-case");
        String user = """
                Incident: %s - %s (service: %s, env: %s)
                Severity: %s | Category: %s
                Probable causes: %s
                Evidence: %s
                Diagnosis confidence: %d%%
                %s
                """.formatted(incident.incidentId(), fence(incident.title()), incident.service(),
                incident.environment(), triage.severity(), triage.category(),
                fence(String.join("; ", diagnosis.probableCauses())),
                fence(String.join("; ", diagnosis.evidence())),
                diagnosis.confidencePct(), catalog.promptContract());

        RemediationPlan plan = llm.call(AgentCallSpec.of(NAME, system(), user, RemediationPlan.class, 0.1)).value();
        log.info("[{}] -> {} steps, autonomous={}", NAME,
                plan.steps() == null ? 0 : plan.steps().size(), plan.isFullyAutonomous());
        return plan;
    }

}
