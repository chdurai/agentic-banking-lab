package io.github.chdurai.agenticbanking.knowledge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeGraphTest {

    private final KnowledgeGraph graph = new KnowledgeGraph();

    private static List<String> ids(List<KnowledgeGraph.Reached> reached) {
        return reached.stream().map(r -> r.node().id()).toList();
    }

    @Test
    void blastRadiusOfDatabaseIncludesEveryTransitiveDependent() {
        assertThat(ids(graph.blastRadius("loan-db")))
                .contains("loan-service", "decision-engine", "api-gateway", "customer-portal", "mobile-app")
                .doesNotContain("loan-db", "notification-service");
    }

    @Test
    void dependenciesAreTransitive() {
        assertThat(ids(graph.dependencies("loan-service")))
                .contains("loan-db", "kyc-service", "sanctions-api", "credit-bureau-adapter");
    }

    @Test
    void runbooksIncludeThoseOfDependencies() {
        assertThat(graph.runbooksFor("loan-service")).extracting(KnowledgeGraph.Node::id)
                .contains("RB-DB-001", "RB-API-002");
    }

    @Test
    void resolvesFreeTextServiceNames() {
        assertThat(graph.resolve("Loan Service")).hasValueSatisfying(n -> assertThat(n.id()).isEqualTo("loan-service"));
        assertThat(graph.resolve("loan_service")).isPresent();
        assertThat(graph.resolve("nonexistent-thing")).isEmpty();
    }

    @Test
    void unknownEntityYieldsSafeEmptyResults() {
        assertThat(graph.blastRadius("nope")).isEmpty();
        assertThat(graph.view("nope")).isEmpty();
        assertThat(graph.describe("nope")).contains("No knowledge-graph entry");
    }

    @Test
    void describeGroundsAPromptWithOwnerAndImpact() {
        String text = graph.describe("loan-db");
        assertThat(text).contains("dba-team", "Loan Service", "RB-DB-001");
    }
}
