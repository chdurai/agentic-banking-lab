package io.github.chdurai.agenticbanking.incident;

import io.github.chdurai.agenticbanking.rag.KeywordRunbookRetriever;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RunbookRetrieverTest {

    private final KeywordRunbookRetriever retriever = new KeywordRunbookRetriever();

    @Test
    void loadsRunbooks() {
        assertThat(retriever.clauseCount()).isGreaterThanOrEqualTo(9);
        assertThat(retriever.allCitations())
                .anyMatch(c -> c.startsWith("RB-DB-001"))
                .anyMatch(c -> c.startsWith("RB-API-002"))
                .anyMatch(c -> c.startsWith("RB-SEC-003"));
    }

    @Test
    void connectionPoolSymptomsMatchDbRunbook() {
        List<String> results = retriever.retrieve(
                "connection pool exhausted timeout HikariCP latency", 2);
        assertThat(results).isNotEmpty();
        assertThat(results.getFirst()).contains("RB-DB-001");
    }

    @Test
    void errorSpikeSymptomsMatchApiRunbook() {
        List<String> results = retriever.retrieve("5xx error rate spike after deployment rollback", 2);
        assertThat(results).isNotEmpty();
        assertThat(results.stream().anyMatch(r -> r.contains("RB-API-002"))).isTrue();
    }

    @Test
    void securitySymptomsMatchSecurityRunbook() {
        List<String> results = retriever.retrieve(
                "impossible travel login authentication failure breach credentials", 2);
        assertThat(results).isNotEmpty();
        assertThat(results.stream().anyMatch(r -> r.contains("RB-SEC-003"))).isTrue();
    }
}
