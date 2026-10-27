package io.github.chdurai.agenticbanking.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KeywordPolicyRetrieverTest {

    private final KeywordPolicyRetriever retriever = new KeywordPolicyRetriever();

    @Test
    void loadsPolicyClausesFromClasspath() {
        assertThat(retriever.clauseCount()).isGreaterThanOrEqualTo(10);
        assertThat(retriever.allCitations())
                .anyMatch(c -> c.startsWith("POL-CRD-001"))
                .anyMatch(c -> c.startsWith("POL-AML-002"))
                .anyMatch(c -> c.startsWith("POL-FLN-003"));
    }

    @Test
    void dtiQueryRetrievesCreditPolicySection3() {
        List<String> results = retriever.retrieve(
                "debt-to-income DTI ratio exceeds maximum underwriting", 3);
        assertThat(results).isNotEmpty();
        // fair-lending §2 also matches strongly (it quotes DTI example text) - both are relevant
        assertThat(results.stream().anyMatch(r -> r.contains("POL-CRD-001 §3"))).isTrue();
    }

    @Test
    void sanctionsQueryRetrievesAmlPolicy() {
        List<String> results = retriever.retrieve("sanctions OFAC screening match decline", 2);
        assertThat(results).isNotEmpty();
        assertThat(results.getFirst()).contains("POL-AML-002 §1");
    }

    @Test
    void adverseActionQueryRetrievesFairLendingPolicy() {
        List<String> results = retriever.retrieve(
                "adverse action notice declined specific reasons ECOA", 3);
        assertThat(results).isNotEmpty();
        assertThat(results.stream().anyMatch(r -> r.contains("POL-FLN-003"))).isTrue();
    }

    @Test
    void irrelevantQueryReturnsEmpty() {
        assertThat(retriever.retrieve("zebra giraffe quantum", 3)).isEmpty();
    }
}
