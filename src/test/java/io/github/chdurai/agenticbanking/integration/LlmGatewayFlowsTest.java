package io.github.chdurai.agenticbanking.integration;

import io.github.chdurai.agenticbanking.approval.ApprovalQueue;
import io.github.chdurai.agenticbanking.approval.ApprovalRequest;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** WI-06 at flow level: when the LLM path is unavailable, every flow degrades to a human. */
@SpringBootTest(properties = "spring.ai.model.chat=none")
@Import(LlmGatewayFlowsTest.StubConfig.class)
class LlmGatewayFlowsTest {

    @TestConfiguration
    static class StubConfig {
        @Bean
        StubChatModel stubChatModel() {
            return new StubChatModel();
        }
    }

    private static final AtomicInteger IDS = new AtomicInteger();

    @Autowired StubChatModel llm;
    @Autowired LlmGateway gateway;
    @Autowired LoanOrchestrator loans;
    @Autowired IncidentOrchestrator incidents;
    @Autowired GrievanceOrchestrator grievances;
    @Autowired ApprovalQueue approvals;

    @BeforeEach
    void reset() {
        llm.reset();
        gateway.setKillSwitch(false);
    }

    @AfterEach
    void release() {
        gateway.setKillSwitch(false);
    }

    @Test
    void killSwitchSendsLoansToManualReviewWithoutAnyModelCall() {
        gateway.setKillSwitch(true);
        LoanApplication a = new LoanApplication("KS" + IDS.incrementAndGet(), "Jane Cooper", "4321",
                new BigDecimal("145000"), 8, new BigDecimal("35000"), "auto", 60,
                new BigDecimal("1200"), "Acme Corp", List.of("paystubs.pdf"));

        LoanDecision d = loans.evaluate(a);

        assertThat(d.decision()).isEqualTo(LoanDecision.Decision.MANUAL_REVIEW);
        assertThat(llm.totalCalls()).isZero();
        assertThat(approvals.list(ApprovalRequest.Status.PENDING))
                .anyMatch(r -> r.subjectId().equals(a.applicantId()));
    }

    @Test
    void killSwitchEscalatesIncidentsAndGrievances() {
        gateway.setKillSwitch(true);
        IncidentResolution i = incidents.handle(new Incident("INC-KS" + IDS.incrementAndGet(),
                "Loan API timing out", "Database pool exhausted", "loan-service", "production", "sre", List.of()));
        assertThat(i.status()).isEqualTo(IncidentResolution.Status.ESCALATED);

        GrievanceResolution g = grievances.handle(new Grievance("GRV-KS" + IDS.incrementAndGet(), "PRJ-1",
                "Wages were paid late.", "WEB", "District 4", false));
        assertThat(g.status()).isEqualTo(GrievanceResolution.Status.PENDING_HUMAN_REVIEW);
        assertThat(llm.totalCalls()).isZero();
    }

    @Test
    void routesAreExposedForOperators() {
        assertThat(gateway.snapshot()).containsKeys("provider", "killSwitch", "breakers", "routes");
        assertThat(gateway.snapshot().get("routes").toString()).contains("decision");
    }
}
