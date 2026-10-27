package io.github.chdurai.agenticbanking.rag;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** WI-08 acceptance: hallucinated citations are stripped; altered values fail grounding. */
class CitationAndGroundingVerifierTest {

    private static final List<String> RETRIEVED = List.of(
            "POL-CRD-001 §3 v1: Section 3: Debt-to-Income\nTotal DTI must not exceed 50%.",
            "ESF-LAB-001 §2 v1: Section 2: Assessment Steps\nVerify payroll records.");

    @Test
    void citationInRetrievedSetIsVerified() {
        var result = CitationVerifier.verify(
                List.of("ESF-LAB-001 §2", "ESF-LAB-001 §2 v1", "POL-CRD-001 §3"), RETRIEVED);
        assertThat(result.allValid()).isTrue();
        assertThat(result.verified()).hasSize(3);
    }

    @Test
    void fabricatedCitationIsStripped() {
        var result = CitationVerifier.verify(
                List.of("ESF-LAB-001 §2", "NONEXISTENT-PLAYBOOK-999", "POL-CRD-001 §99"), RETRIEVED);
        assertThat(result.allValid()).isFalse();
        assertThat(result.verified()).containsExactly("ESF-LAB-001 §2");
        assertThat(result.stripped()).containsExactlyInAnyOrder("NONEXISTENT-PLAYBOOK-999", "POL-CRD-001 §99");
    }

    @Test
    void citedVersionMismatchIsStripped() {
        var result = CitationVerifier.verify(List.of("POL-CRD-001 §3 v9"), RETRIEVED);
        assertThat(result.stripped()).containsExactly("POL-CRD-001 §3 v9");
    }

    @Test
    void scrubReplacesUnverifiedCitationsInFreeText() {
        String text = "Follow NONEXISTENT-PLAYBOOK-999 and POL-CRD-001 §3 before replying.";
        String scrubbed = CitationVerifier.scrub(text, RETRIEVED);
        assertThat(scrubbed).doesNotContain("NONEXISTENT-PLAYBOOK-999");
        assertThat(scrubbed).contains(CitationVerifier.REMOVED);
        assertThat(scrubbed).contains("POL-CRD-001 §3");
    }

    @Test
    void scrubLeavesCitationFreeTextUntouched() {
        assertThat(CitationVerifier.scrub("plain reply", RETRIEVED)).isEqualTo("plain reply");
        assertThat(CitationVerifier.scrub(null, RETRIEVED)).isNull();
    }

    @Test
    void groundedKeyFieldsPass() {
        var result = GroundingVerifier.verify(
                Map.of("amount", "USD 50,000", "borrower", "Acme Ltd"),
                "Loan agreement: Acme Ltd borrows USD 50,000.00 repayable over 60 months.");
        assertThat(result.allGrounded()).isTrue();
    }

    @Test
    void alteredValueFailsGrounding() {
        var result = GroundingVerifier.verify(
                Map.of("amount", "USD 500,000", "borrower", "Acme Ltd"),
                "Loan agreement: Acme Ltd borrows USD 50,000.00 repayable over 60 months.");
        assertThat(result.allGrounded()).isFalse();
        assertThat(result.ungroundedFields()).containsExactly("amount");
        assertThat(result.groundedFields()).containsExactly("borrower");
    }

    @Test
    void emptyKeyFieldsAreTriviallyGrounded() {
        assertThat(GroundingVerifier.verify(Map.of(), "anything").allGrounded()).isTrue();
        assertThat(GroundingVerifier.verify(null, "anything").allGrounded()).isTrue();
    }

    @Test
    void unrelatedPassagesReorderedWordsAndRepeatedTokensCannotFormOneExtractedValue() {
        String source = "Acme rejected the terms. Another borrower is Beta Ltd. Paid USD 50,000.20.";
        var result = GroundingVerifier.verify(Map.of(
                "joined", "Acme Ltd", "reordered", "Ltd Beta", "duplicate", "Beta Beta",
                "changedDecimal", "USD 50,000.02", "partialAmount", "5000"), source);
        assertThat(result.groundedFields()).isEmpty();
        assertThat(result.ungroundedFields()).hasSize(5);
        assertThat(result.sourceSpans()).isEmpty();
    }

    @Test
    void spansPreserveOriginalOffsetsWithUnicodeWhitespaceAndEquivalentNumberFormatting() {
        String source = "Invoice \uD83D\uDCC4: Soci\u00e9t\u00e9\n  Acme paid USD 48,200.00 yesterday.";
        var result = GroundingVerifier.verify(Map.of("party", "soci\u00e9t\u00e9 Acme", "total", "USD 48200"), source);
        assertThat(result.allGrounded()).isTrue();
        var party = result.sourceSpans().get("party");
        var amount = result.sourceSpans().get("total");
        assertThat(source.substring(party.startInclusive(), party.endExclusive())).isEqualTo("Soci\u00e9t\u00e9\n  Acme");
        assertThat(source.substring(amount.startInclusive(), amount.endExclusive())).isEqualTo("USD 48,200.00");
    }

    @Test
    void numericSignsCurrencyAndNonLatinValuesCannotDisappearFromTheGroundingCheck() {
        assertThat(GroundingVerifier.verify(Map.of("amount", "48200"), "Total -48200").allGrounded()).isFalse();
        assertThat(GroundingVerifier.verify(Map.of("amount", "$48200"), "Total \u20ac48200").allGrounded()).isFalse();
        assertThat(GroundingVerifier.verify(Map.of("amount", "48200"), "Reference 48200ABC").allGrounded()).isFalse();
        assertThat(GroundingVerifier.verify(Map.of("party", "\u6771\u4eac"), "Office \u6771\u4eac").allGrounded()).isTrue();
        assertThat(GroundingVerifier.verify(Map.of("party", "\u6771\u4eac"), "Office \u5927\u962a").allGrounded()).isFalse();
    }
}
