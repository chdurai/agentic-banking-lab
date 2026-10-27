package io.github.chdurai.agenticbanking.integration;

import io.github.chdurai.agenticbanking.approval.ApprovalQueue;
import io.github.chdurai.agenticbanking.approval.ApprovalRequest;
import io.github.chdurai.agenticbanking.model.LoanApplication;
import io.github.chdurai.agenticbanking.model.LoanDecision;
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

/** WI-36 end to end: platform.flow.challenge=v2 against the stub LLM. */
@SpringBootTest(properties = {"spring.ai.model.chat=none", "agents.planner-enabled=true",
        "platform.flow.state=v2", "platform.flow.challenge=v2"})
@Import(ChallengeProtocolTest.StubConfig.class)
class ChallengeProtocolTest {

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
    @Autowired ApprovalQueue approvals;

    @BeforeEach
    void reset() {
        llm.reset();
    }

    private static LoanApplication app() {
        return new LoanApplication("CP" + IDS.incrementAndGet(), "Jane Cooper", "4321",
                new BigDecimal("145000"), 8, new BigDecimal("35000"), "auto", 60,
                new BigDecimal("1200"), "Acme Corp", List.of("paystubs.pdf"));
    }

    private List<ApprovalRequest> pendingFor(String subjectId) {
        return approvals.list(ApprovalRequest.Status.PENDING).stream()
                .filter(a -> subjectId.equals(a.subjectId())).toList();
    }

    @Test
    void cleanCaseApprovesWithNoRevisions() {
        LoanDecision d = loans.evaluate(app());
        assertThat(d.decision()).isEqualTo(LoanDecision.Decision.APPROVED);
        assertThat(d.revisionCount()).isZero();
        assertThat(llm.callsTo("critic")).isEqualTo(1);
    }

    @Test
    void criticFeedbackTriggersOneRedraft() {
        llm.critiques.clear();
        llm.critiques.add(StubChatModel.critique(false));
        llm.critiques.add(StubChatModel.critique(true));

        LoanDecision d = loans.evaluate(app());

        assertThat(d.revisionCount()).isEqualTo(1);
        assertThat(llm.callsTo("decision")).isEqualTo(2);
        assertThat(d.decision()).isEqualTo(LoanDecision.Decision.APPROVED);
    }

    @Test
    void stuckCriticStopsEarlyAndHandsTheOpenChallengeToTheReviewer() {
        llm.critiques.clear();
        for (int i = 0; i < 5; i++) {
            llm.critiques.add(StubChatModel.critique(false));
        }
        LoanApplication application = app();

        LoanDecision d = loans.evaluate(application);

        assertThat(d.decision()).isEqualTo(LoanDecision.Decision.MANUAL_REVIEW);
        // Non-convergence: the second identical objection ends the loop instead of burning the whole budget.
        assertThat(llm.callsTo("critic")).isEqualTo(2);
        assertThat(pendingFor(application.applicantId())).singleElement()
                .satisfies(a -> assertThat(a.details()).anyMatch(l -> l.contains("Open challenge [CRITIC]")));
    }

    @Test
    void detectorCatchesApprovalOverAFailedAgentWithoutAskingTheCritic() {
        llm.agentReports.put("credit-risk", StubChatModel.report("credit-risk", "FAIL", 30));
        llm.decisions.clear();
        llm.decisions.add(StubChatModel.decision("APPROVED"));
        llm.decisions.add(StubChatModel.decision("MANUAL_REVIEW"));

        LoanDecision d = loans.evaluate(app());

        assertThat(d.decision()).isNotEqualTo(LoanDecision.Decision.APPROVED);
        assertThat(llm.callsTo("decision")).isGreaterThanOrEqualTo(2);
    }

    @Test
    void criticOutageStillFailsSafe() {
        llm.failingAgents.add("critic");
        LoanDecision d = loans.evaluate(app());
        assertThat(d.decision()).isEqualTo(LoanDecision.Decision.MANUAL_REVIEW);
    }
}
