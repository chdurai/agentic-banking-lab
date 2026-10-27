package io.github.chdurai.agenticbanking.rag;

import io.github.chdurai.agenticbanking.security.CallerContext;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

class TenantRetrievalTest {
    @Test void privateClausesCitationsAndRankingAreScopedBeforeRetrieval() {
        var retriever = new AbstractKeywordRetriever("classpath:tenant-corpus/*.txt", "tenant-fixture") {};
        assertThat(retriever.retrieve("orchard lending", 5)).isEmpty();
        for (String tenant : java.util.List.of("alpha", "beta")) {
            try (var ignored = new CallerContext(tenant, "reader", Set.of("KNOWLEDGE_READER")).open()) {
                assertThat(retriever.clauseCount()).isEqualTo(1);
                assertThat(retriever.allCitations()).hasSize(1);
                assertThat(retriever.retrieve("orchard lending", 5)).hasSize(1)
                        .allSatisfy(text -> assertThat(text.toLowerCase()).contains(tenant).doesNotContain(tenant.equals("alpha") ? "beta" : "alpha"));
            }
        }
    }
}
