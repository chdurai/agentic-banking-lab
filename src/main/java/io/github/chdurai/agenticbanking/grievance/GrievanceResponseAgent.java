package io.github.chdurai.agenticbanking.grievance;

import io.github.chdurai.agenticbanking.llm.AgentCallSpec;
import io.github.chdurai.agenticbanking.llm.LlmGateway;
import io.github.chdurai.agenticbanking.guardrails.Spotlight;
import io.github.chdurai.agenticbanking.rag.GrievancePlaybookRetriever;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/** Grievance agent 2: playbook-grounded response draft for the case officer. */
@Component
public class GrievanceResponseAgent {

    private static final Logger log = LoggerFactory.getLogger(GrievanceResponseAgent.class);
    static final String NAME = "grievance-response";

    private static final String SYSTEM = """
            You are a grievance response drafter supporting a case officer. Using ONLY the retrieved
            playbook sections as guidance, produce:
            - acknowledgement: a short, respectful, plain-language reply to the complainant. Confirm receipt,
              say what happens next and the timeline given, and how to follow up. Promise no specific outcome,
              compensation or disciplinary action.
            - proposedActions: concrete next steps for the case officer, each tied to a playbook citation.
            - citedPlaybooks: the citations (e.g. "ESF-LAB-001 §3") you relied on.
            - followUpPlan: who follows up, and by when.
            If the playbooks do not cover the issue, say so in proposedActions rather than inventing procedure.
            """ + Spotlight.INSTRUCTION;

    private final LlmGateway llm;
    private final GrievancePlaybookRetriever playbooks;
    private final io.github.chdurai.agenticbanking.capability.CapabilityGuard capabilities;

    public GrievanceResponseAgent(LlmGateway llm,
                                  GrievancePlaybookRetriever playbooks,
                                  io.github.chdurai.agenticbanking.capability.CapabilityGuard capabilities,
                                  io.github.chdurai.agenticbanking.config.AgentProperties agentProperties) {
        this.llm = llm;
        this.playbooks = playbooks;
        this.capabilities = capabilities;
        this.agentProperties = agentProperties;
    }

    private final io.github.chdurai.agenticbanking.config.AgentProperties agentProperties;

    public GrievanceResponseDraft draft(Grievance g, GrievanceTriage triage, GrievanceRouting.Decision routing) {
        try (var scope = capabilities.enter(NAME, g.grievanceId())) {
            return draftScoped(g, triage, routing);
        }
    }

    private GrievanceResponseDraft draftScoped(Grievance g, GrievanceTriage triage, GrievanceRouting.Decision routing) {
        capabilities.checkView("grievance-case");
        capabilities.checkRetrieval("grievance-playbooks");
        List<String> sections = playbooks.retrieve(g.description() + " " + triage.themes() + " " + triage.summary(),
                3, agentProperties.getRagMinScore());
        String user = """
                Triage: themes=%s, severity=%s, sentiment=%s, retaliationRisk=%s, vulnerableGroups=%s
                Routing: %s, response SLA %d hours (%s)

                Retrieved playbook sections:
                %s

                Grievance:
                %s
                """.formatted(triage.themes(), triage.severity(), triage.sentiment(), triage.retaliationRisk(),
                triage.vulnerableGroupsAffected(), routing.route(), routing.slaHours(), routing.reason(),
                sections.isEmpty() ? "None matched." : String.join("\n\n", sections),
                Spotlight.wrap(g.description()));
        GrievanceResponseDraft draft = llm.call(AgentCallSpec.of(NAME, SYSTEM, user, GrievanceResponseDraft.class, 0.2)).value();
        log.info("[{}] -> {} actions", NAME,
                draft.proposedActions() == null ? 0 : draft.proposedActions().size());
        return verifyCitations(draft, sections);
    }

    /**
     * WI-08: every cited playbook must be in the retrieved set. Fabricated citations are
     * stripped from the citation list and scrubbed from free text before the draft can be
     * stored or shown - a hallucinated procedure reference never leaves the agent.
     */
    static GrievanceResponseDraft verifyCitations(GrievanceResponseDraft draft, List<String> retrieved) {
        var result = io.github.chdurai.agenticbanking.rag.CitationVerifier.verify(draft.citedPlaybooks(), retrieved);
        if (result.allValid()) {
            return draft;
        }
        return new GrievanceResponseDraft(
                io.github.chdurai.agenticbanking.rag.CitationVerifier.scrub(draft.acknowledgement(), retrieved),
                draft.proposedActions() == null ? null : draft.proposedActions().stream()
                        .map(a -> io.github.chdurai.agenticbanking.rag.CitationVerifier.scrub(a, retrieved)).toList(),
                result.verified(),
                io.github.chdurai.agenticbanking.rag.CitationVerifier.scrub(draft.followUpPlan(), retrieved));
    }
}
