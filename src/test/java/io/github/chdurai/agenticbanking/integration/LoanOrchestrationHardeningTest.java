package io.github.chdurai.agenticbanking.integration;

import io.github.chdurai.agenticbanking.memory.CaseMemoryStore;
import io.github.chdurai.agenticbanking.model.AgentReport;
import io.github.chdurai.agenticbanking.model.LoanApplication;
import io.github.chdurai.agenticbanking.model.LoanContext;
import io.github.chdurai.agenticbanking.model.LoanDecision;
import io.github.chdurai.agenticbanking.orchestrator.ExecutionPlan;
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
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** WI-04: default (rule-based) planning, plan validation, collect-all stages, UNAVAILABLE semantics. */
@SpringBootTest(properties = "spring.ai.model.chat=none")
@Import(LoanOrchestrationHardeningTest.StubConfig.class)
class LoanOrchestrationHardeningTest {

    @TestConfiguration
    static class StubConfig {
        @Bean
        StubChatModel stubChatModel() {
            return new StubChatModel();
        }
    }

    private static final AtomicInteger IDS = new AtomicInteger();
    private static final Set<String> KNOWN = Set.of("intake", "kyc", "credit-risk", "compliance");

    @Autowired StubChatModel llm;
    @Autowired LoanOrchestrator loans;
    @Autowired CaseMemoryStore memory;

    @BeforeEach
    void reset() {
        llm.reset();
    }

    private static LoanApplication app() {
        return new LoanApplication("H" + IDS.incrementAndGet(), "Jane Cooper", "4321",
                new BigDecimal("145000"), 8, new BigDecimal("35000"), "auto", 60,
                new BigDecimal("1200"), "Acme Corp", List.of("paystubs.pdf"));
    }

    @Test
    void ruleBasedPlanIsDefaultAndMakesNoPlannerCall() {
        LoanDecision d = loans.evaluate(app());

        assertThat(d.decision()).isEqualTo(LoanDecision.Decision.APPROVED);
        assertThat(llm.callsTo("planner")).isZero();
        assertThat(d.agentReports()).hasSize(4);
    }

    @Test
    void plansThatDropRegulatoryAgentsOrMisorderDependenciesAreRejected() {
        assertThatThrownBy(() -> ExecutionPlan.validate(
                List.of(List.of("intake"), List.of("credit-risk"), List.of("compliance")), "x", KNOWN))
                .hasMessageContaining("kyc");
        assertThatThrownBy(() -> ExecutionPlan.validate(
                List.of(List.of("intake"), List.of("kyc", "compliance")), "x", KNOWN))
                .hasMessageContaining("compliance must run alone and last");
        assertThatThrownBy(() -> ExecutionPlan.validate(
                List.of(List.of("kyc"), List.of("intake"), List.of("compliance")), "x", KNOWN))
                .hasMessageContaining("intake must run alone and first");
        assertThat(ExecutionPlan.validate(
                List.of(List.of("intake"), List.of("kyc"), List.of("compliance")), "kyc only", KNOWN).stages())
                .hasSize(3);
    }

    @Test
    void failedSiblingReportIsRetainedWhenAnotherAgentHardFails() {
        llm.agentReports.put("kyc", StubChatModel.report("kyc", "FAIL", 100));

        LoanApplication a = app();
        LoanDecision customer = loans.evaluate(a);

        assertThat(customer.decision()).isEqualTo(LoanDecision.Decision.COMPLIANCE_HOLD);
        LoanDecision audit = memory.latestFor(a.applicantId()).orElseThrow();
        assertThat(audit.agentReports()).extracting(AgentReport::agentName)
                .contains("intake", "kyc", "credit-risk");
        assertThat(llm.callsTo("compliance")).isZero();
    }

    @Test
    void agentOutageIsUnavailableNotAVerdictAndRoutesToHuman() {
        llm.failingAgents.add("intake");

        LoanDecision d = loans.evaluate(app());

        assertThat(d.agentReports()).filteredOn(r -> r.agentName().equals("intake"))
                .singleElement().satisfies(r -> assertThat(r.status()).isEqualTo(AgentReport.Status.UNAVAILABLE));
        assertThat(d.decision()).isEqualTo(LoanDecision.Decision.MANUAL_REVIEW);
    }

    @Test
    void stageSnapshotIsFrozenAgainstLaterReports() {
        LoanContext ctx = new LoanContext(app());
        ctx.addReport(new AgentReport("intake", AgentReport.Status.PASS, 5, "ok", List.of(), List.of()));
        LoanContext snap = ctx.snapshot();
        ctx.addReport(new AgentReport("kyc", AgentReport.Status.PASS, 5, "ok", List.of(), List.of()));

        assertThat(snap.reports()).hasSize(1);
        assertThat(snap.priorFindings()).doesNotContain("kyc");
        assertThat(ctx.reports()).hasSize(2);
    }
}
