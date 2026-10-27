package io.github.chdurai.agenticbanking.rag;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WI-08 RAG v2 acceptance: minimum score filters weak matches, effective-date windows
 * exclude expired/future policy, uniform citation format, retrieved-nothing is explicit.
 */
class RagV2Test {

    private final KeywordPolicyRetriever retriever = new KeywordPolicyRetriever();

    @Test
    void citationsUseUniformFormatWithVersion() {
        assertThat(retriever.allCitations())
                .allMatch(c -> c.matches("[A-Z]+-[A-Z]+-\\d+ §\\d+ v\\d+"), "uniform DOC §N vX format")
                .anyMatch(c -> c.startsWith("POL-CRD-001"));
    }

    @Test
    void minScoreDropsWeakBoilerplateMatches() {
        String query = "debt-to-income DTI ratio exceeds maximum underwriting";
        List<String> unfiltered = retriever.retrieve(query, 10, 0.0);
        List<String> filtered = retriever.retrieve(query, 10, 2.0);
        assertThat(filtered).isNotEmpty();
        assertThat(filtered.size()).isLessThanOrEqualTo(unfiltered.size());
        assertThat(filtered.stream().anyMatch(r -> r.contains("POL-CRD-001 §3"))).isTrue();
    }

    @Test
    void absurdMinScoreRetrievesNothing() {
        assertThat(retriever.retrieve("debt-to-income DTI ratio", 3, 1_000_000.0)).isEmpty();
    }

    @Test
    void expiredDocumentContributesNoClauses() {
        String doc = """
                # Old Credit Policy (POL-OLD-001)
                <!-- meta: version=v1; effectiveFrom=2020-01-01; effectiveTo=2021-12-31 -->

                ## Section 1: Limits
                Loans capped at $5,000.
                """;
        assertThat(AbstractKeywordRetriever.parseDocument(doc, "old.md", LocalDate.parse("2026-10-22")))
                .as("expired policy").isEmpty();
        assertThat(AbstractKeywordRetriever.parseDocument(doc, "old.md", LocalDate.parse("2021-06-01")))
                .as("in force").hasSize(1);
    }

    @Test
    void futureDocumentIsNotYetEffective() {
        String doc = """
                # Future Policy (POL-NEW-001)
                <!-- meta: version=v2; effectiveFrom=2030-01-01 -->

                ## Section 1: Limits
                Loans capped at $500,000.
                """;
        assertThat(AbstractKeywordRetriever.parseDocument(doc, "new.md", LocalDate.parse("2026-10-22")))
                .isEmpty();
        var clauses = AbstractKeywordRetriever.parseDocument(doc, "new.md", LocalDate.parse("2030-01-01"));
        assertThat(clauses).hasSize(1);
        assertThat(clauses.getFirst().citation().format()).isEqualTo("POL-NEW-001 §1 v2");
    }

    @Test
    void documentWithoutMetadataDefaultsToV1AlwaysEffective() {
        String doc = """
                # Plain Policy (POL-PLN-001)

                ## Section 2: Terms
                Standard terms apply.
                """;
        var clauses = AbstractKeywordRetriever.parseDocument(doc, "plain.md", LocalDate.now());
        assertThat(clauses).hasSize(1);
        assertThat(clauses.getFirst().citation().format()).isEqualTo("POL-PLN-001 §2 v1");
    }

    @Test
    void irrelevantQueryStillRetrievesNothing() {
        assertThat(retriever.retrieve("zebra giraffe quantum", 3)).isEmpty();
        assertThat(retriever.retrieve("zebra giraffe quantum", 3, 1.0)).isEmpty();
    }
}
