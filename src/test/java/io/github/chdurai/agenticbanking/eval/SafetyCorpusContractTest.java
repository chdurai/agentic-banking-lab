package io.github.chdurai.agenticbanking.eval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static io.github.chdurai.agenticbanking.eval.SafetyEvaluation.Check.*;

class SafetyCorpusContractTest {
    @Test
    void allFoundationCorporaArePresentAndDoNotShrinkSilently() {
        var entries = SafetyEvaluation.load();
        assertThat(entries.stream().collect(Collectors.groupingBy(SafetyEvaluation.Entry::corpus,
                Collectors.counting()))).isEqualTo(Map.of("incident", 11L, "grievance", 15L, "adversarial", 12L));
        assertThat(entries).extracting(e -> e.testCase().id()).doesNotHaveDuplicates();
        var tagged = entries.stream().filter(e -> e.testCase().expectedFailureUntil() != null).toList();
        assertThat(tagged).as("no open known gaps; register a WI with a pinned failing check when one appears")
                .isEmpty();
    }

    @Test
    void englishAndThreeOtherLanguagesHaveSensitiveAndBenignCases() {
        var cases = SafetyEvaluation.load().stream().filter(e -> e.corpus().equals("grievance"))
                .map(SafetyEvaluation.Entry::testCase).toList();
        for (String language : List.of("en", "es", "fr", "sw")) {
            var languageCases = cases.stream().filter(c -> c.language().equals(language)).toList();
            assertThat(languageCases).as(language).anySatisfy(c -> {
                assertThat(c.expected().get(STATUS).asText()).isEqualTo("CONFIDENTIAL_ESCALATION");
                assertThat(c.expected().get(MODEL_CALLS).intValue()).isZero();
                assertThat(c.expected().get(SENSITIVE_CONTENT_RETAINED).booleanValue()).isFalse();
            }).anySatisfy(c -> assertThat(c.expected().get(STATUS).asText()).isEqualTo("ROUTED"));
        }
        assertThat(cases).anySatisfy(c -> {
            assertThat(c.language()).isEqualTo("ja");
            assertThat(c.expected().get(STATUS).asText()).isEqualTo("PENDING_HUMAN_REVIEW");
            assertThat(c.expected().get(MODEL_CALLS).intValue()).isZero();
        });
    }

    @Test
    void confidentialCasesRequirePrivacyChecksAndBlockedCasesRequireZeroCalls() {
        SafetyEvaluation.load().forEach(entry -> {
            var expected = entry.testCase().expected();
            String status = expected.get(STATUS).asText();
            if (status.equals("CONFIDENTIAL_ESCALATION")) {
                assertThat(expected.get(SENSITIVE_CONTENT_RETAINED).booleanValue()).isFalse();
                assertThat(expected.get(APPROVAL_COUNT).intValue()).isEqualTo(1);
            }
            if (status.equals("CONFIDENTIAL_ESCALATION") || status.equals("BLOCKED")) {
                assertThat(expected.get(MODEL_CALLS).intValue()).isZero();
                assertThat(expected.get(EXECUTION_COUNT).intValue()).isZero();
            }
        });
    }
}
