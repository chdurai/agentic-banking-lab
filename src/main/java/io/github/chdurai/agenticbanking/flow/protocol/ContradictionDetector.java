package io.github.chdurai.agenticbanking.flow.protocol;

import io.github.chdurai.agenticbanking.flow.state.Finding;
import io.github.chdurai.agenticbanking.model.AgentReport;
import io.github.chdurai.agenticbanking.model.LoanDecision;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Code-first contradiction finder (v3.1 §3.7 rule 1). Pure: no model, no I/O, same input → same issues.
 * It only reports what facts can prove; judgement-type concerns are left to the LLM critic.
 * Pricing and cap mismatches are not raised here: the invariant checker flags them and the policy merge
 * repairs them deterministically.
 */
@Component
public class ContradictionDetector {

    public static final String APPROVED_DESPITE_FAIL = "APPROVED_DESPITE_FAIL";
    public static final String UNGROUNDED_RISK_SCORE = "UNGROUNDED_RISK_SCORE";
    public static final String CONFLICTING_FACT = "CONFLICTING_FACT";

    private static final Pattern CITED_SCORE = Pattern.compile("(?i)risk\\s*score\\s*(?:of|is|=|:)?\\s*(\\d{1,3})\\b");

    /** Fact keys that are per-agent by nature and so never "the same fact" across agents. */
    private static final Set<String> PER_AGENT_KEYS = Set.of("riskScore");

    public List<ChallengeIssue> detect(LoanDecision draft, List<AgentReport> reports, List<Finding> findings) {
        List<ChallengeIssue> issues = new ArrayList<>();

        if (draft.decision() == LoanDecision.Decision.APPROVED) {
            reports.stream().filter(AgentReport::failed).forEach(r -> issues.add(new ChallengeIssue(
                    APPROVED_DESPITE_FAIL, ChallengeIssue.Severity.BLOCKING, ChallengeIssue.DECISION,
                    "Decision is APPROVED but " + r.agentName() + " reported FAIL", ChallengeIssue.Source.DETECTOR)));
        }

        String reasoning = draft.reasoning();
        if (reasoning != null && !reports.isEmpty()) {
            Set<Integer> known = new HashSet<>();
            reports.forEach(r -> known.add(r.riskScore()));
            known.add((int) Math.round(reports.stream().mapToInt(AgentReport::riskScore).average().orElse(0)));
            Matcher m = CITED_SCORE.matcher(reasoning);
            while (m.find()) {
                int cited = Integer.parseInt(m.group(1));
                if (!known.contains(cited)) {
                    issues.add(new ChallengeIssue(UNGROUNDED_RISK_SCORE, ChallengeIssue.Severity.ADVISORY,
                            ChallengeIssue.DECISION, "Reasoning cites risk score " + cited
                            + " which no agent reported (reported: " + known + ")", ChallengeIssue.Source.DETECTOR));
                }
            }
        }

        issues.addAll(conflictingFacts(findings));
        return issues;
    }

    /** Two agents asserting different values for the same fact key; the later asserter is challenged. */
    List<ChallengeIssue> conflictingFacts(List<Finding> findings) {
        Map<String, Map<String, Object>> byKey = new LinkedHashMap<>();
        List<ChallengeIssue> out = new ArrayList<>();
        for (Finding f : findings) {
            for (Map.Entry<String, Object> fact : f.facts().entrySet()) {
                if (PER_AGENT_KEYS.contains(fact.getKey())) {
                    continue;
                }
                Map<String, Object> asserters = byKey.computeIfAbsent(fact.getKey(), k -> new LinkedHashMap<>());
                for (Map.Entry<String, Object> earlier : asserters.entrySet()) {
                    if (!earlier.getKey().equals(f.agentId()) && !Objects.equals(earlier.getValue(), fact.getValue())) {
                        out.add(new ChallengeIssue(CONFLICTING_FACT, ChallengeIssue.Severity.BLOCKING, f.agentId(),
                                "Fact '" + fact.getKey() + "' is " + fact.getValue() + " per " + f.agentId()
                                        + " but " + earlier.getValue() + " per " + earlier.getKey(),
                                ChallengeIssue.Source.DETECTOR));
                    }
                }
                asserters.put(f.agentId(), fact.getValue());
            }
        }
        return out;
    }
}
