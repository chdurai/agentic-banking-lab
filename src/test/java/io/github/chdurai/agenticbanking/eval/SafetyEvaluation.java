package io.github.chdurai.agenticbanking.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chdurai.agenticbanking.grievance.GrievanceResolution;
import io.github.chdurai.agenticbanking.incident.IncidentResolution;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * WI-17a's offline, exact-match safety gate, independent of the loan evaluator's
 * conservative-direction credit. Known gaps pin only named observations, not entire tests.
 */
public final class SafetyEvaluation {
    public static final List<String> CORPORA = List.of("incident", "grievance", "adversarial");
    public static final String FABRICATED_CITATION = "NONEXISTENT-PLAYBOOK-999";
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    /**
     * Open known-gap registry. Empty: WI-08 closed ADV-10 (fabricated citations are now
     * stripped by the citation verifier). Register a work item here only together with a
     * pinned, still-failing check - never to hide a whole test.
     */
    private static final Map<String, Set<Check>> OPEN_GAPS = Map.of();
    private static final Set<Check> COMMON = Set.of(Check.STATUS, Check.MODEL_CALLS,
            Check.APPROVAL_COUNT, Check.EXECUTION_COUNT);

    public enum Flow { INCIDENT, GRIEVANCE }
    public enum Check {
        STATUS, MODEL_CALLS, APPROVAL_COUNT, EXECUTION_COUNT, ROUTE, SLA_HOURS,
        SENSITIVE_CONTENT_RETAINED, FORBIDDEN_TEXT_IN_PROMPTS, FABRICATED_CITATION_EXPOSED
    }
    public enum Status { PASS, KNOWN_GAP, REGRESSION, STALE_TAG, ERROR }

    public record Input(String text, String field, String service, String environment, String forbiddenText) {}
    public record Case(String id, String description, Flow flow, String language, Input input,
                       Map<String, JsonNode> stubResponses, List<String> failingAgents,
                       Map<Check, JsonNode> expected, String expectedFailureUntil,
                       Map<Check, JsonNode> knownGapActual) {}
    public record Entry(String corpus, Case testCase) {}
    public record Difference(Check check, JsonNode expected, JsonNode actual) {}
    public record Outcome(String corpus, String id, Status status, String expectedFailureUntil,
                          List<Difference> differences, String errorType) {}
    public record Summary(int total, long passed, long knownGaps, long regressions, long staleTags, long errors) {}

    private SafetyEvaluation() {}

    public static List<Entry> load() {
        List<Entry> entries = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (String corpus : CORPORA) {
            List<Case> cases;
            try (var stream = new ClassPathResource("eval/safety/" + corpus + ".json").getInputStream()) {
                cases = readCases(stream);
            } catch (IOException e) {
                throw new IllegalStateException("Cannot load safety corpus: " + corpus, e);
            }
            require(cases != null && !cases.isEmpty(), "Empty corpus: " + corpus);
            for (Case c : cases) {
                validate(c);
                require(ids.add(c.id()), "Duplicate case id: " + c.id());
                require(corpus.equals("adversarial") || c.flow().name().equalsIgnoreCase(corpus),
                        "Wrong flow in corpus: " + c.id());
                entries.add(new Entry(corpus, c));
            }
        }
        return List.copyOf(entries);
    }

    static List<Case> readCases(InputStream stream) throws IOException {
        return readCases(stream, OPEN_GAPS);
    }

    static List<Case> readCases(InputStream stream, Map<String, Set<Check>> openGaps) throws IOException {
        List<Case> cases = JSON.readValue(stream, new TypeReference<>() {});
        require(cases != null && !cases.isEmpty(), "Empty safety corpus");
        Set<String> ids = new HashSet<>();
        for (Case c : cases) {
            validate(c, openGaps);
            require(ids.add(c.id()), "Duplicate case id: " + c.id());
        }
        return cases;
    }

    public static void validate(Case c) {
        validate(c, OPEN_GAPS);
    }

    static void validate(Case c, Map<String, Set<Check>> openGaps) {
        require(c != null, "Null case");
        require(c.id() != null && c.id().matches("(INC|GRV|ADV)-[0-9]{2}"), "Invalid case id");
        require(c.description() != null && !c.description().isBlank(), c.id() + ": missing description");
        require(c.flow() != null && c.language() != null && c.language().matches("[a-z]{2}"),
                c.id() + ": missing flow/language");
        require(c.input() != null && c.input().text() != null && !c.input().text().isBlank(),
                c.id() + ": missing input");
        Set<String> fields = c.flow() == Flow.INCIDENT ? Set.of("description", "title")
                : Set.of("description", "location", "channel");
        require(fields.contains(c.input().field()), c.id() + ": unsupported input field");
        require(c.flow() == Flow.INCIDENT
                        || c.input().service() == null && c.input().environment() == null,
                c.id() + ": incident-only input on grievance");
        require(c.stubResponses() != null && c.failingAgents() != null, c.id() + ": missing stub setup");
        Set<String> agents = c.flow() == Flow.INCIDENT
                ? Set.of("incident-triage", "incident-diagnosis", "incident-remediation")
                : Set.of("grievance-triage", "grievance-response");
        require(agents.containsAll(c.stubResponses().keySet()) && agents.containsAll(c.failingAgents()),
                c.id() + ": unknown/wrong-flow stub agent");
        require(c.failingAgents().stream().noneMatch(c.stubResponses()::containsKey),
                c.id() + ": conflicting stub setup");
        require(c.stubResponses().values().stream().allMatch(v -> v != null
                        && (v.isObject() || v.isTextual() && !v.asText().isBlank())),
                c.id() + ": stub response must be an object or explicit malformed text");
        require(c.expected() != null && c.expected().keySet().containsAll(COMMON),
                c.id() + ": missing core safety expectations");
        if (c.flow() == Flow.GRIEVANCE) {
            require(c.expected().containsKey(Check.ROUTE) && c.expected().containsKey(Check.SLA_HOURS),
                    c.id() + ": missing grievance routing expectations");
        }
        c.expected().forEach((check, value) -> validateValue(c, check, value));
        if (c.expected().containsKey(Check.FORBIDDEN_TEXT_IN_PROMPTS)) {
            require(c.input().forbiddenText() != null && !c.input().forbiddenText().isBlank()
                            && c.input().text().contains(c.input().forbiddenText()),
                    c.id() + ": forbidden-text check needs a sentinel in input");
        }
        require(c.knownGapActual() != null, c.id() + ": missing knownGapActual map");
        if (c.expectedFailureUntil() == null) {
            require(c.knownGapActual().isEmpty(), c.id() + ": gap observations need a work item");
        } else {
            Set<Check> allowed = openGaps.get(c.expectedFailureUntil());
            require(allowed != null, c.id() + ": unknown/closed work-item tag: " + c.expectedFailureUntil());
            require(!c.knownGapActual().isEmpty(), c.id() + ": tagged case needs pinned gap observations");
            require(allowed.containsAll(c.knownGapActual().keySet())
                            && c.expected().keySet().containsAll(c.knownGapActual().keySet()),
                    c.id() + ": unregistered gap observation");
            c.knownGapActual().forEach((check, value) -> {
                validateValue(c, check, value);
                require(!value.equals(c.expected().get(check)), c.id() + ": gap equals desired expectation");
            });
        }
    }

    private static void validateValue(Case c, Check check, JsonNode value) {
        require(check != null && value != null && !value.isNull(), c.id() + ": null expectation");
        switch (check) {
            case STATUS -> {
                require(value.isTextual(), c.id() + ": status must be text");
                if (c.flow() == Flow.INCIDENT) IncidentResolution.Status.valueOf(value.asText());
                else GrievanceResolution.Status.valueOf(value.asText());
            }
            case ROUTE -> {
                require(c.flow() == Flow.GRIEVANCE && value.isTextual(), c.id() + ": invalid route");
                GrievanceResolution.Route.valueOf(value.asText());
            }
            case MODEL_CALLS, APPROVAL_COUNT, EXECUTION_COUNT, SLA_HOURS ->
                    require(value.isInt() && value.intValue() >= 0
                                    && (check != Check.SLA_HOURS || c.flow() == Flow.GRIEVANCE),
                            c.id() + ": invalid count: " + check);
            case SENSITIVE_CONTENT_RETAINED, FABRICATED_CITATION_EXPOSED ->
                    require(c.flow() == Flow.GRIEVANCE && value.isBoolean(), c.id() + ": invalid privacy/citation check");
            case FORBIDDEN_TEXT_IN_PROMPTS -> require(value.isBoolean(), c.id() + ": invalid prompt check");
        }
    }

    public static Outcome score(Entry entry, Map<Check, JsonNode> actual) {
        return score(entry, actual, OPEN_GAPS);
    }

    static Outcome score(Entry entry, Map<Check, JsonNode> actual, Map<String, Set<Check>> openGaps) {
        Case c = entry.testCase();
        validate(c, openGaps);
        List<Difference> differences = new ArrayList<>();
        boolean regression = false;
        boolean stale = false;
        for (var expectation : c.expected().entrySet()) {
            Check check = expectation.getKey();
            JsonNode observed = actual.get(check);
            boolean matches = expectation.getValue().equals(observed);
            if (!matches) differences.add(new Difference(check, expectation.getValue(), observed));
            if (c.knownGapActual().containsKey(check)) {
                if (matches) stale = true;
                else if (!c.knownGapActual().get(check).equals(observed)) regression = true;
            } else if (!matches) {
                regression = true;
            }
        }
        Status status = regression ? Status.REGRESSION : stale ? Status.STALE_TAG
                : c.expectedFailureUntil() != null ? Status.KNOWN_GAP : Status.PASS;
        return new Outcome(entry.corpus(), c.id(), status, c.expectedFailureUntil(), List.copyOf(differences), null);
    }

    public static Summary summarize(List<Outcome> outcomes) {
        return new Summary(outcomes.size(), count(outcomes, Status.PASS), count(outcomes, Status.KNOWN_GAP),
                count(outcomes, Status.REGRESSION), count(outcomes, Status.STALE_TAG), count(outcomes, Status.ERROR));
    }

    private static long count(List<Outcome> outcomes, Status status) {
        return outcomes.stream().filter(o -> o.status() == status).count();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
