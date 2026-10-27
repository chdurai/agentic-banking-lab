package io.github.chdurai.agenticbanking.incident;

import io.github.chdurai.agenticbanking.llm.AgentCallSpec;
import io.github.chdurai.agenticbanking.llm.LlmGateway;
import io.github.chdurai.agenticbanking.knowledge.KnowledgeGraph;
import io.github.chdurai.agenticbanking.memory.EpisodicMemoryStore;
import io.github.chdurai.agenticbanking.rag.RunbookRetriever;
import io.github.chdurai.agenticbanking.tools.IncidentLogQueryTools;
import io.github.chdurai.agenticbanking.tools.IncidentObservabilityTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Incident agent 2: root-cause diagnosis grounded in retrieved runbook sections
 * (RAG). Must cite matched runbook IDs.
 */
@Component
public class IncidentDiagnosisAgent {

    private static final Logger log = LoggerFactory.getLogger(IncidentDiagnosisAgent.class);
    static final String NAME = "incident-diagnosis";

    private static final String SYSTEM = """
            You are a site reliability engineer diagnosing an incident. Use the retrieved
            runbook sections as ground truth. Produce:
            - probableCauses: ranked list, most likely first, each tied to evidence.
            - evidence: observations from the report supporting the diagnosis.
            - matchedRunbooks: citations of the runbook sections you relied on (e.g. RB-DB-001 §2).
            - confidencePct: 0-100. Below 50 means escalate to a human SRE.
            If the symptoms match no runbook, say so explicitly in probableCauses.
            """;

    private final io.github.chdurai.agenticbanking.flow.FlowKit flow;
    private final LlmGateway llm;
    private final RunbookRetriever runbooks;
    private final KnowledgeGraph graph;
    private final IncidentObservabilityTools observabilityTools;
    private final IncidentLogQueryTools logQueryTools;
    private final EpisodicMemoryStore episodic;

    public IncidentDiagnosisAgent(LlmGateway llm,
                                  RunbookRetriever runbooks, KnowledgeGraph graph,
                                  IncidentObservabilityTools observabilityTools,
                                  IncidentLogQueryTools logQueryTools,
                                  EpisodicMemoryStore episodic,
                                  io.github.chdurai.agenticbanking.flow.FlowKit flow,
                                  io.github.chdurai.agenticbanking.capability.CapabilityGuard capabilities,
                                  io.github.chdurai.agenticbanking.config.AgentProperties agentProperties) {
        this.flow = flow;
        this.llm = llm;
        this.runbooks = runbooks;
        this.graph = graph;
        this.observabilityTools = observabilityTools;
        this.logQueryTools = logQueryTools;
        this.episodic = episodic;
        this.capabilities = capabilities;
        this.agentProperties = agentProperties;
    }

    private final io.github.chdurai.agenticbanking.capability.CapabilityGuard capabilities;
    private final io.github.chdurai.agenticbanking.config.AgentProperties agentProperties;

    private String system() {
        return flow.v2() ? SYSTEM + "\n" + io.github.chdurai.agenticbanking.guardrails.Spotlight.INSTRUCTION : SYSTEM;
    }

    private String fence(String text) {
        return flow.v2() ? flow.prompts().untrusted(text) : text;
    }

    public DiagnosisReport diagnose(Incident incident, IncidentTriage triage) {
        try (var scope = capabilities.enter(NAME, incident.incidentId())) {
            return diagnoseScoped(incident, triage);
        }
    }

    private DiagnosisReport diagnoseScoped(Incident incident, IncidentTriage triage) {
        capabilities.checkView("incident-case");
        String query = incident.title() + " " + incident.description() + " "
                + triage.category() + " symptoms diagnosis";
        capabilities.checkRetrieval("runbooks");
        List<String> sections = runbooks.retrieve(query, 3, agentProperties.getRagMinScore());
        List<String> similar = episodic.similarIncidents(incident.service(),
                        symptomKey(incident, triage), 2)
                .stream().map(EpisodicMemoryStore.Entry::summary).toList();

        boolean boundedToolUse = "bounded-tool-use".equalsIgnoreCase(agentProperties.toolModeFor(NAME));
        String user = """
                Incident: %s - %s
                Service: %s (%s)
                Triage: category=%s, severity=%s
                Symptoms/description:
                %s

                Service context from the enterprise knowledge graph (dependencies, blast radius, owners):
                %s

                Retrieved runbook sections (cite these):
                %s
                Similar verified incidents (sanitized episodic memory; cite as "episodic memory"):
                %s
                %s
                """.formatted(incident.incidentId(), fence(incident.title()), incident.service(),
                incident.environment(), triage.category(), triage.severity(), fence(incident.description()),
                graph.describe(incident.service()),
                sections.isEmpty() ? "No matching runbooks found." : String.join("\n\n", sections),
                similar.isEmpty() ? "None" : String.join("\n- ", similar),
                boundedToolUse
                        ? "\nBounded tool-use mode is enabled. You may call read-only diagnostics tools "
                        + "(metrics/log queries) only when needed and keep calls minimal."
                        : "\nDeterministic-evidence mode: use only the provided evidence; do not call tools.");

        AgentCallSpec<DiagnosisReport> spec = AgentCallSpec.of(NAME, system(), user, DiagnosisReport.class, 0.1);
        if (boundedToolUse) {
            spec = spec.withTools(observabilityTools, logQueryTools);
        }
        DiagnosisReport report = llm.call(spec).value();
        log.info("[{}] -> confidence={}% causes={}", NAME,
                report.confidencePct(), report.probableCauses().size());
        return verifyCitations(report, sections);
    }

    static String symptomKey(Incident incident, IncidentTriage triage) {
        return triage.category() + "|" + incident.title();
    }

    /** WI-08: matched-runbook citations must come from the retrieved set; unknown ones are stripped. */
    static DiagnosisReport verifyCitations(DiagnosisReport report, List<String> retrieved) {
        var result = io.github.chdurai.agenticbanking.rag.CitationVerifier.verify(report.matchedRunbooks(), retrieved);
        if (result.allValid()) {
            return report;
        }
        return new DiagnosisReport(report.probableCauses(), report.evidence(),
                result.verified(), report.confidencePct());
    }

}
