package io.github.chdurai.agenticbanking.integration;

import io.github.chdurai.agenticbanking.documents.DocumentRequest;
import io.github.chdurai.agenticbanking.documents.DocumentResult;
import io.github.chdurai.agenticbanking.documents.DocumentService;
import io.github.chdurai.agenticbanking.grievance.Grievance;
import io.github.chdurai.agenticbanking.grievance.GrievanceOrchestrator;
import io.github.chdurai.agenticbanking.grievance.GrievanceResolution;
import io.github.chdurai.agenticbanking.incident.Incident;
import io.github.chdurai.agenticbanking.incident.IncidentOrchestrator;
import io.github.chdurai.agenticbanking.incident.IncidentResolution;
import io.github.chdurai.agenticbanking.llm.LlmGateway;
import io.github.chdurai.agenticbanking.model.LoanApplication;
import io.github.chdurai.agenticbanking.model.LoanDecision;
import io.github.chdurai.agenticbanking.orchestrator.LoanOrchestrator;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.ai.model.chat=none", "spring.ai.model.embedding=none",
        "spring.ai.mcp.client.enabled=false", "spring.ai.mcp.server.enabled=false",
        "agents.anomaly.enabled=false", "platform.cases.worker-enabled=false",
        "platform.llm.provider-error-budget.enabled=true",
        "platform.llm.provider-error-budget.sliding-window-size=2",
        "platform.llm.provider-error-budget.minimum-calls=2",
        "platform.llm.provider-error-budget.wait-open-ms=60000",
        "platform.llm.resilience.retry.initial-backoff-ms=1",
        "platform.llm.resilience.retry.jitter=0"
})
@Import(ProviderErrorBudgetFlowTest.Config.class)
class ProviderErrorBudgetFlowTest {
    static class OfflineModel extends StubChatModel {
        final AtomicInteger attempts = new AtomicInteger();
        @Override public ChatResponse call(Prompt prompt) {
            attempts.incrementAndGet();
            throw new TransientAiException("503 synthetic provider outage");
        }
    }

    @TestConfiguration static class Config {
        @Bean OfflineModel offlineModel() { return new OfflineModel(); }
    }

    @Autowired OfflineModel model;
    @Autowired LlmGateway gateway;
    @Autowired DocumentService documents;
    @Autowired LoanOrchestrator loans;
    @Autowired IncidentOrchestrator incidents;
    @Autowired GrievanceOrchestrator grievances;

    @Test void exhaustedProviderBudgetDegradesAllFourFlowsWithoutAdditionalProviderCalls() {
        var document = documents.analyze(new DocumentRequest("budget-doc", "Invoice", "Total 48200"));
        assertThat(document.status()).isEqualTo(DocumentResult.Status.NEEDS_REVIEW);
        assertThat(document.approvalId()).isNotBlank();
        assertThat(model.attempts).hasValue(2);
        assertThat(gateway.snapshot().get("providerErrorBudget").toString()).contains("state=OPEN");

        var incident = incidents.handle(new Incident("budget-incident", "Database timeout",
                "Database connections are timing out", "loan-db", "production", "synthetic", List.of()));
        assertThat(incident.status()).isEqualTo(IncidentResolution.Status.ESCALATED);
        assertThat(incident.approvalId()).isNotBlank();
        assertThat(incident.executionLog()).isEmpty();

        var grievance = grievances.handle(new Grievance("budget-grievance", "synthetic-project",
                "Road construction causes dust near the water point", "WEB", "Main road", true));
        assertThat(grievance.status()).isEqualTo(GrievanceResolution.Status.PENDING_HUMAN_REVIEW);
        assertThat(grievance.approvalId()).isNotBlank();

        var loan = loans.evaluate(new LoanApplication("budget-loan", "Synthetic Applicant", "4321",
                new BigDecimal("145000"), 8, new BigDecimal("35000"), "home renovation", 60,
                new BigDecimal("1200"), "Synthetic Employer", List.of("paystubs.pdf")));
        assertThat(loan.decision()).isEqualTo(LoanDecision.Decision.MANUAL_REVIEW);
        assertThat(model.attempts).hasValue(2);
        assertThat(gateway.killSwitchOn()).isFalse();
    }
}
