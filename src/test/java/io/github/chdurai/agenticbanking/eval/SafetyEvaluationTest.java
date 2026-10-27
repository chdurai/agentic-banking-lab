package io.github.chdurai.agenticbanking.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static io.github.chdurai.agenticbanking.eval.SafetyEvaluation.Check.*;
import static io.github.chdurai.agenticbanking.eval.SafetyEvaluation.Status.*;

class SafetyEvaluationTest {
    private final ObjectMapper json = new ObjectMapper();

    /** Test-local open-gap registry so gap-mechanics tests survive real gaps being closed. */
    private static final Map<String, Set<SafetyEvaluation.Check>> TEST_GAPS =
            Map.of("WI-08", Set.of(FABRICATED_CITATION_EXPOSED));

    private SafetyEvaluation.Entry entry(String id) {
        return SafetyEvaluation.load().stream().filter(e -> e.testCase().id().equals(id)).findFirst().orElseThrow();
    }

    /** ADV-10 as it looked while WI-08 was open: tagged, with the fabricated citation still exposed. */
    private SafetyEvaluation.Entry taggedAdv10() {
        var node = caseNode("ADV-10");
        node.put("expectedFailureUntil", "WI-08");
        node.set("knownGapActual", json.createObjectNode().put("FABRICATED_CITATION_EXPOSED", true));
        try {
            var cases = SafetyEvaluation.readCases(new ByteArrayInputStream(
                    json.writeValueAsString(List.of(node)).getBytes(StandardCharsets.UTF_8)), TEST_GAPS);
            return new SafetyEvaluation.Entry("adversarial", cases.getFirst());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<SafetyEvaluation.Check, JsonNode> knownActual(SafetyEvaluation.Entry entry) {
        var actual = new HashMap<>(entry.testCase().expected());
        actual.putAll(entry.testCase().knownGapActual());
        return actual;
    }

    @Test
    void exactChecksPassWithoutConservativeDirectionCredit() {
        var entry = entry("INC-01");
        var actual = knownActual(entry);
        assertThat(SafetyEvaluation.score(entry, actual).status()).isEqualTo(PASS);
        actual.put(STATUS, json.valueToTree("ESCALATED"));
        assertThat(SafetyEvaluation.score(entry, actual).status()).isEqualTo(REGRESSION);
    }

    @Test
    void dangerousDirectionAndExecutionRegressionsFail() {
        var entry = entry("INC-02");
        var actual = knownActual(entry);
        actual.put(STATUS, json.valueToTree("AUTO_REMEDIATED"));
        actual.put(EXECUTION_COUNT, json.valueToTree(1));
        var outcome = SafetyEvaluation.score(entry, actual);
        assertThat(outcome.status()).isEqualTo(REGRESSION);
        assertThat(outcome.differences()).extracting(SafetyEvaluation.Difference::check)
                .containsExactlyInAnyOrder(STATUS, EXECUTION_COUNT);
    }

    @Test
    void knownGapOnlyAcceptsPinnedObservedFailure() {
        var entry = taggedAdv10();
        var outcome = SafetyEvaluation.score(entry, knownActual(entry), TEST_GAPS);
        assertThat(outcome.status()).isEqualTo(KNOWN_GAP);
        assertThat(outcome.expectedFailureUntil()).isEqualTo("WI-08");
        assertThat(outcome.differences()).extracting(SafetyEvaluation.Difference::check)
                .containsExactly(FABRICATED_CITATION_EXPOSED);
    }

    @Test
    void resolvingGapFailsUntilTagIsRemoved() {
        var entry = taggedAdv10();
        assertThat(SafetyEvaluation.score(entry, entry.testCase().expected(), TEST_GAPS).status())
                .isEqualTo(STALE_TAG);
    }

    @Test
    void taggedCaseCannotHideAnUnrelatedFailure() {
        var entry = taggedAdv10();
        var actual = knownActual(entry);
        actual.put(MODEL_CALLS, json.valueToTree(0));
        assertThat(SafetyEvaluation.score(entry, actual, TEST_GAPS).status()).isEqualTo(REGRESSION);
    }

    @Test
    void missingOrDifferentGapObservationIsNotAKnownGap() {
        var entry = taggedAdv10();
        var actual = knownActual(entry);
        actual.remove(FABRICATED_CITATION_EXPOSED);
        assertThat(SafetyEvaluation.score(entry, actual, TEST_GAPS).status()).isEqualTo(REGRESSION);
        actual.put(FABRICATED_CITATION_EXPOSED, json.valueToTree("true"));
        assertThat(SafetyEvaluation.score(entry, actual, TEST_GAPS).status()).isEqualTo(REGRESSION);
    }

    @Test
    void resolvedGapDoesNotHideAnotherRegression() {
        var entry = taggedAdv10();
        var actual = new HashMap<>(entry.testCase().expected());
        actual.put(APPROVAL_COUNT, json.valueToTree(1));
        assertThat(SafetyEvaluation.score(entry, actual, TEST_GAPS).status()).isEqualTo(REGRESSION);
    }

    @Test
    void summaryKeepsKnownGapsAndErrorsSeparateFromPasses() {
        var pass = entry("INC-01");
        var gap = taggedAdv10();
        var error = new SafetyEvaluation.Outcome("incident", "INC-09", ERROR, null, List.of(),
                IllegalStateException.class.getName());
        var summary = SafetyEvaluation.summarize(List.of(
                SafetyEvaluation.score(pass, knownActual(pass)),
                SafetyEvaluation.score(gap, knownActual(gap), TEST_GAPS),
                SafetyEvaluation.score(gap, gap.testCase().expected(), TEST_GAPS),
                SafetyEvaluation.score(pass, Map.of()), error));
        assertThat(summary).isEqualTo(new SafetyEvaluation.Summary(5, 1, 1, 1, 1, 1));
        assertThat(SafetyEvaluation.summarize(List.of()))
                .isEqualTo(new SafetyEvaluation.Summary(0, 0, 0, 0, 0, 0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"WI-999", "WI-09", "WI-17", "", "WI-08 "})
    void unknownOrClosedTagsAreRejected(String tag) {
        var node = caseNode("ADV-10");
        node.put("expectedFailureUntil", tag);
        node.set("knownGapActual", json.createObjectNode().put("FABRICATED_CITATION_EXPOSED", true));
        assertThatThrownBy(() -> parse(node, TEST_GAPS)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown/closed");
    }

    @Test
    void blanketAndUnpinnedGapExemptionsAreRejected() {
        var node = caseNode("ADV-10");
        node.put("expectedFailureUntil", "WI-08");
        node.set("knownGapActual", json.createObjectNode().put("STATUS", "ROUTED"));
        assertThatThrownBy(() -> parse(node, TEST_GAPS)).hasMessageContaining("unregistered gap observation");
        node.set("knownGapActual", json.createObjectNode());
        assertThatThrownBy(() -> parse(node, TEST_GAPS)).hasMessageContaining("pinned gap observations");
        node.set("knownGapActual", json.createObjectNode().put("FABRICATED_CITATION_EXPOSED", false));
        assertThatThrownBy(() -> parse(node, TEST_GAPS)).hasMessageContaining("gap equals desired");
    }

    @Test
    void gapObservationsWithoutWorkItemAreRejected() {
        var node = caseNode("ADV-10");
        node.set("knownGapActual", json.createObjectNode().put("FABRICATED_CITATION_EXPOSED", true));
        assertThatThrownBy(() -> parse(node)).hasMessageContaining("need a work item");
    }

    @Test
    void missingCoreChecksWrongTypesAndUnknownStubAgentsAreRejected() {
        var node = caseNode("INC-01");
        ((ObjectNode) node.get("expected")).remove("EXECUTION_COUNT");
        assertThatThrownBy(() -> parse(node)).hasMessageContaining("core safety expectations");
        var wrongType = caseNode("INC-01");
        ((ObjectNode) wrongType.get("expected")).put("MODEL_CALLS", "3");
        assertThatThrownBy(() -> parse(wrongType)).hasMessageContaining("invalid count");
        var wrongAgent = caseNode("INC-01");
        ((ObjectNode) wrongAgent.get("stubResponses")).put("typo-agent", "{}");
        assertThatThrownBy(() -> parse(wrongAgent)).hasMessageContaining("stub agent");
    }

    @Test
    void malformedUnknownDuplicateAndTrailingJsonCannotBeIgnored() throws IOException {
        var node = caseNode("INC-01");
        node.put("expectedFailUntil", "WI-08");
        assertThatThrownBy(() -> parse(node)).isInstanceOf(IOException.class);
        String valid = json.writeValueAsString(List.of(caseNode("INC-01")));
        assertThatThrownBy(() -> read(valid + " []")).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> read(valid.replace("\"id\":\"INC-01\"", "\"id\":\"INC-01\",\"id\":\"INC-02\"")))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> read("[")).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> read("[]")).hasMessageContaining("Empty safety corpus");
        assertThatThrownBy(() -> read("[" + caseNode("INC-01") + "," + caseNode("INC-01") + "]"))
                .hasMessageContaining("Duplicate case id");
    }

    private ObjectNode caseNode(String id) {
        return json.valueToTree(entry(id).testCase());
    }

    private List<SafetyEvaluation.Case> parse(ObjectNode node) throws IOException {
        return read(json.writeValueAsString(List.of(node)));
    }

    private List<SafetyEvaluation.Case> parse(ObjectNode node, Map<String, Set<SafetyEvaluation.Check>> gaps)
            throws IOException {
        return SafetyEvaluation.readCases(new ByteArrayInputStream(
                json.writeValueAsString(List.of(node)).getBytes(StandardCharsets.UTF_8)), gaps);
    }

    private List<SafetyEvaluation.Case> read(String content) throws IOException {
        return SafetyEvaluation.readCases(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
    }
}
