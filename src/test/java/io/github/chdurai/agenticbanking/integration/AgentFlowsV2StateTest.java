package io.github.chdurai.agenticbanking.integration;

import io.github.chdurai.agenticbanking.incident.Incident;
import io.github.chdurai.agenticbanking.incident.IncidentOrchestrator;
import io.github.chdurai.agenticbanking.incident.IncidentResolution;
import io.github.chdurai.agenticbanking.model.LoanApplication;
import io.github.chdurai.agenticbanking.model.LoanDecision;
import io.github.chdurai.agenticbanking.orchestrator.LoanOrchestrator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** platform.flow.state=v2 must not change the outcome of an ordinary case. */
@SpringBootTest(properties = {"spring.ai.model.chat=none", "agents.planner-enabled=true", "platform.flow.state=v2"})
@Import(AgentFlowsV2StateTest.StubConfig.class)
class AgentFlowsV2StateTest {

    @TestConfiguration
    static class StubConfig {
        @Bean
        StubChatModel stubChatModel() {
            return new StubChatModel();
        }
    }

    @Autowired LoanOrchestrator loans;
    @Autowired IncidentOrchestrator incidents;

    @Test
    void ordinaryLoanStillCompletesUnderTypedState() {
        LoanDecision d = loans.evaluate(new LoanApplication("V2-1", "Jane Cooper", "4321",
                new BigDecimal("145000"), 8, new BigDecimal("35000"), "home improvement", 60,
                new BigDecimal("1200"), "Acme Corp", List.of("paystubs.pdf")));
        assertThat(d.decision()).isNotNull();
        assertThat(d.decision()).isIn(LoanDecision.Decision.APPROVED, LoanDecision.Decision.MANUAL_REVIEW);
    }

    @Test
    void incidentFlowRunsUnderTypedState() {
        IncidentResolution r = incidents.handle(new Incident("INC-V2-1", "Loan API timing out",
                "p99 latency 12s, connection pool exhausted", "loan-service", "production", "sre", List.of()));
        assertThat(r).isNotNull();
    }
}
