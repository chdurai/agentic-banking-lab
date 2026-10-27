package io.github.chdurai.agenticbanking.integration;

import io.github.chdurai.agenticbanking.approval.ApprovalQueue;
import io.github.chdurai.agenticbanking.approval.ApprovalRequest;
import io.github.chdurai.agenticbanking.grievance.Grievance;
import io.github.chdurai.agenticbanking.grievance.GrievanceOrchestrator;
import io.github.chdurai.agenticbanking.ops.PlatformEvent;
import io.github.chdurai.agenticbanking.ops.PlatformEventListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Platform events are the audit/event-driven seam for the target microservices architecture. */
@SpringBootTest(properties = "spring.ai.model.chat=none")
@Import(AgentFlowsStubbedTest.StubConfig.class)
class PlatformEventsStubbedTest {

    private static final AtomicInteger IDS = new AtomicInteger();

    @Autowired StubChatModel llm;
    @Autowired GrievanceOrchestrator grievances;
    @Autowired ApprovalQueue approvals;
    @Autowired PlatformEventListener events;

    @BeforeEach
    void reset() {
        llm.reset();
    }

    @Test
    void workflowOutcomesAndApprovalDecisionsAreEmitted() {
        String id = "GRV-E" + IDS.incrementAndGet();
        // Retaliation risk -> human review -> approval request created
        llm.fixed.put("grievance-triage",
                StubChatModel.grievanceTriage("MODERATE", "STAKEHOLDER_ENGAGEMENT", true, false, 90));
        grievances.handle(new Grievance(id, "PRJ-1", "They keep pressuring me to withdraw.", "WEB", "x", false));

        ApprovalRequest pending = approvals.list(ApprovalRequest.Status.PENDING).stream()
                .filter(r -> r.subjectId().equals(id)).findFirst().orElseThrow();
        approvals.decide(pending.id(), true, "case-officer", "looks right");

        List<PlatformEvent> recent = events.recent(50);
        assertThat(recent).anyMatch(e -> e.type().equals("grievance.handled") && e.subjectId().equals(id));
        assertThat(recent).anyMatch(e -> e.type().equals("approval.decided") && e.subjectId().equals(id)
                && e.summary().contains("APPROVED"));
    }
}
