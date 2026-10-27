package io.github.chdurai.agenticbanking.flow.prompt;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.chdurai.agenticbanking.flow.FlowProperties;
import io.github.chdurai.agenticbanking.flow.state.CaseState;
import io.github.chdurai.agenticbanking.flow.state.Finding;
import io.github.chdurai.agenticbanking.guardrails.Spotlight;
import io.github.chdurai.agenticbanking.guardrails.TextNormalizer;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The only place that turns case state into prompt text. Deterministic (byte-equal for equal state),
 * renders only the agents an agent is declared to read, facts as sorted JSON, and every piece of
 * free text (narratives, observations, applicant strings) normalised, length-capped and Spotlighted.
 */
@Component
public class PromptBuilder {

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private final FlowProperties props;

    public PromptBuilder(FlowProperties props) {
        this.props = props;
    }

    /** @param reads agent ids the caller may see, in rendering order; null means every agent. */
    public String render(CaseState state, List<String> reads) {
        List<Finding> view = state.currentView().stream()
                .filter(f -> reads == null || reads.contains(f.agentId()))
                .sorted(java.util.Comparator.comparingInt((Finding f) -> reads == null ? 0 : reads.indexOf(f.agentId())))
                .toList();
        if (view.isEmpty()) {
            return "No prior agent findings.";
        }
        StringBuilder sb = new StringBuilder();
        for (Finding f : view) {
            sb.append("- agent=").append(f.agentId()).append(" status=").append(f.status())
              .append(" facts=").append(facts(f)).append('\n');
            if (f.status() == Finding.Status.UNAVAILABLE) {
                sb.append("  (check not performed; do not treat as a verdict on the applicant)\n");
            }
            StringBuilder text = new StringBuilder(cap(f.narrative()));
            f.observations().forEach(o -> text.append("\nobservation: ").append(cap(o)));
            f.riskSignals().forEach(s -> text.append("\nred flag: ").append(cap(s.description())));
            sb.append(indent(Spotlight.wrap(text.toString()))).append('\n');
        }
        return sb.toString();
    }

    /** Wraps one untrusted string (applicant or reporter supplied) for inclusion in a prompt. */
    public String untrusted(String text) {
        return Spotlight.wrap(cap(text));
    }

    private String cap(String text) {
        String s = TextNormalizer.normalize(text).display();
        if (s == null) {
            return "";
        }
        int max = props.getMaxNarrativeChars();
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    private static String facts(Finding f) {
        try {
            return JSON.writeValueAsString(f.facts());
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private static String indent(String s) {
        return "  " + s.replace("\n", "\n  ");
    }
}
