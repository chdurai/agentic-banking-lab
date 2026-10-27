package io.github.chdurai.agenticbanking.integration;

import io.github.chdurai.agenticbanking.approval.ApprovalQueue;
import io.github.chdurai.agenticbanking.incident.Incident;
import io.github.chdurai.agenticbanking.incident.IncidentOrchestrator;
import io.github.chdurai.agenticbanking.incident.IncidentResolution;
import io.github.chdurai.agenticbanking.model.LoanApplication;
import io.github.chdurai.agenticbanking.model.LoanDecision;
import io.github.chdurai.agenticbanking.ops.ExecutionTraceStore;
import io.github.chdurai.agenticbanking.orchestrator.LoanOrchestrator;
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

/** platform.flow.runtime=dag (WI-35) must reach the same outcomes as the stage-barrier runtime. */
@SpringBootTest(properties = {"spring.ai.model.chat=none", "agents.planner-enabled=true",
        "platform.flow.runtime=dag", "platform.flow.state=v2"})
@Import(AgentFlowsDagRuntimeTest.StubConfig.class)
class AgentFlowsDagRuntimeTest {

    @TestConfiguration
    static class StubConfig {
        @Bean
        StubChatModel stubChatModel() {
            return new StubChatModel();
        }
    }

    private static final AtomicInteger IDS = new AtomicInteger();

    @Autowired StubChatModel llm;
    @Autowired LoanOrchestrator loans;
    @Autowired IncidentOrchestrator incidents;
    @Autowired ExecutionTraceStore traces;
    @Autowired ApprovalQueue approvals;

    @BeforeEach
    void reset() {
        llm.reset();
    }

    private static LoanApplication app() {
        return new LoanApplication("DAG" + IDS.incrementAndGet(), "Jane Cooper", "4321",
                new BigDecimal("145000"), 8, new BigDecimal("35000"), "home renovation", 60,
                new BigDecimal("1200"), "Acme Corp", List.of("paystubs.pdf"));
    }

    @Test
    void happyPathMakesOneCallPerAgentAndTracesEachNode() {
        LoanApplication a = app();
        LoanDecision d = loans.evaluate(a);

        assertThat(d.decision()).isEqualTo(LoanDecision.Decision.APPROVED);
        assertThat(d.agentReports()).hasSize(4);
        for (String agent : List.of("intake", "kyc", "credit-risk", "compliance", "decision", "critic")) {
            assertThat(llm.callsTo(agent)).as(agent).isEqualTo(1);
        }
        assertThat(traces.recent(50)).filteredOn(t -> a.applicantId().equals(t.correlationId()))
                .extracting(ExecutionTraceStore.TraceEntry::agent)
                .containsExactlyInAnyOrder("intake", "kyc", "credit-risk", "compliance");
    }

    @Test
    void reportsAreCommittedInPlanOrderRegardlessOfFinishOrder() {
        LoanDecision d = loans.evaluate(app());
        assertThat(d.agentReports()).extracting(r -> r.agentName())
                .startsWith("intake").endsWith("compliance");
    }

    @Test
    void hostilePlanFallsBackToRulePlanAndStillRunsRequiredAgents() {
        // compliance first and kyc missing: the planner cannot remove a required agent or reorder reads
        llm.fixed.put("planner", "{\"stages\":[[\"compliance\"],[\"credit-risk\"]],\"rationale\":\"skip kyc\"}");
        LoanDecision d = loans.evaluate(app());

        assertThat(d.decision()).isEqualTo(LoanDecision.Decision.APPROVED);
        assertThat(llm.callsTo("kyc")).isEqualTo(1);
        assertThat(llm.callsTo("intake")).isEqualTo(1);
    }

    @Test
    void plannerOrderingIsHonouredSoSanctionsHitSkipsLaterAgents() {
        llm.fixed.put("planner",
                "{\"stages\":[[\"intake\"],[\"kyc\"],[\"credit-risk\"],[\"compliance\"]],\"rationale\":\"x\"}");
        llm.agentReports.put("kyc", StubChatModel.report("kyc", "FAIL", 100));

        LoanDecision d = loans.evaluate(app());

        assertThat(d.decision()).isEqualTo(LoanDecision.Decision.COMPLIANCE_HOLD);
        assertThat(llm.callsTo("credit-risk")).isZero();
        assertThat(llm.callsTo("compliance")).isZero();
        assertThat(llm.callsTo("decision")).isZero();
    }

    @Test
    void incidentOutageStillFailsSafeThroughTheSharedRuntime() {
        llm.failingAgents.add("incident-diagnosis");
        Incident i = new Incident("INC-DAG-" + IDS.incrementAndGet(), "Loan API timing out",
                "Database pool exhausted on loan-service", "api-gateway", "staging", "sre", List.of());
        IncidentResolution r = incidents.handle(i);

        assertThat(r.status()).isEqualTo(IncidentResolution.Status.ESCALATED);
        assertThat(r.guardrailFlags()).contains("AGENT_UNAVAILABLE");
        assertThat(llm.callsTo("incident-remediation")).isZero();
        assertThat(traces.recent(20)).anySatisfy(t -> {
            assertThat(t.correlationId()).isEqualTo(i.incidentId());
            assertThat(t.agent()).isEqualTo("incident-diagnosis");
            assertThat(t.outcome()).startsWith("ERROR");
        });
    }
}
