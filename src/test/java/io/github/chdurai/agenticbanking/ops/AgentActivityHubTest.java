package io.github.chdurai.agenticbanking.ops;

import io.github.chdurai.agenticbanking.flow.state.AgentMessage;
import io.github.chdurai.agenticbanking.flow.state.Finding;
import io.github.chdurai.agenticbanking.flow.state.InMemoryCaseState;
import io.github.chdurai.agenticbanking.flow.state.Provenance;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class AgentActivityHubTest {

    @Test
    void assignsMonotoneSequenceAndKeepsBacklog() {
        AgentActivityHub hub = new AgentActivityHub();
        hub.agentStarted("loan", "c1", "kyc");
        hub.agentCompleted("loan", "c1", "kyc", 42, "PASS", "OK");

        List<AgentActivityEvent> recent = hub.recent(10);
        assertThat(recent).hasSize(2);
        assertThat(recent.get(0).kind()).isEqualTo(AgentActivityEvent.Kind.AGENT_COMPLETED);
        assertThat(recent.get(0).seq()).isGreaterThan(recent.get(1).seq());
        assertThat(recent.get(0).durationMs()).isEqualTo(42);
        assertThat(recent.get(1).agent()).isEqualTo("kyc");
    }

    @Test
    void notifiesSubscribersAsynchronously() throws InterruptedException {
        AgentActivityHub hub = new AgentActivityHub();
        CountDownLatch latch = new CountDownLatch(1);
        List<AgentActivityEvent> seen = new CopyOnWriteArrayList<>();
        hub.subscribe(e -> {
            seen.add(e);
            latch.countDown();
        });

        hub.agentStarted("incident", "i1", "triage");

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(seen).hasSize(1);
        assertThat(seen.get(0).flow()).isEqualTo("incident");
    }

    @Test
    void platformEventsJoinTheStream() throws InterruptedException {
        AgentActivityHub hub = new AgentActivityHub();
        CountDownLatch latch = new CountDownLatch(1);
        hub.subscribe(e -> latch.countDown());

        hub.onPlatformEvent(PlatformEvent.of("loan.decided", "loan", "c9", "APPROVED"));

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        AgentActivityEvent event = hub.recent(1).get(0);
        assertThat(event.kind()).isEqualTo(AgentActivityEvent.Kind.FLOW);
        assertThat(event.label()).isEqualTo("loan.decided");
        assertThat(event.detail()).isEqualTo("APPROVED");
    }

    @Test
    void caseStateEmitsFindingsAndMessages() throws InterruptedException {
        AgentActivityHub hub = new AgentActivityHub();
        CountDownLatch latch = new CountDownLatch(2);
        hub.subscribe(e -> latch.countDown());
        InMemoryCaseState state = new InMemoryCaseState("c1", "loan", hub);

        state.append(new Finding("f1", "c1", "loan", "kyc", Finding.Kind.ASSESSMENT, Finding.Status.PASS,
                Map.of(), List.of(), "all clear", List.of(), List.of(), null, null,
                Provenance.of("m1", false, 5), false, 1, null));
        state.post(new AgentMessage(null, null, null, 0, "critic", "kyc",
                AgentMessage.Type.CHALLENGE, List.of(), Map.of("claim", "income mismatch"), null, 1));

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        List<AgentActivityEvent> recent = hub.recent(10);
        assertThat(recent).hasSize(2);
        AgentActivityEvent message = recent.get(0);
        assertThat(message.kind()).isEqualTo(AgentActivityEvent.Kind.MESSAGE);
        assertThat(message.agent()).isEqualTo("critic");
        assertThat(message.to()).isEqualTo("kyc");
        assertThat(message.label()).isEqualTo("CHALLENGE");
        assertThat(message.detail()).contains("income mismatch");
        assertThat(message.caseId()).isEqualTo("c1");
        AgentActivityEvent finding = recent.get(1);
        assertThat(finding.kind()).isEqualTo(AgentActivityEvent.Kind.FINDING);
        assertThat(finding.flow()).isEqualTo("loan");
    }

    @Test
    void llmExchangeCapturesPromptAndResponse() throws InterruptedException {
        AgentActivityHub hub = new AgentActivityHub();
        CountDownLatch latch = new CountDownLatch(1);
        hub.subscribe(e -> latch.countDown());

        hub.llmExchange("loan", "c1", "kyc", "You are KYC.", "Check Jane", new io.github.chdurai.agenticbanking.llm.LlmResult<>(
                Map.of("status", "PASS", "riskScore", 12), "kyc", "llama3.2", "v1", "hash", 900, 120, 1450,
                0.002, false, 1));

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        AgentActivityEvent event = hub.recent(1).get(0);
        assertThat(event.kind()).isEqualTo(AgentActivityEvent.Kind.LLM);
        assertThat(event.label()).isEqualTo("llama3.2");
        assertThat(event.durationMs()).isEqualTo(1450);
        assertThat(event.request()).contains("You are KYC.", "Check Jane");
        assertThat(event.response()).contains("\"riskScore\" : 12");
        assertThat(event.detail()).contains("900→120 tok");
    }

    @Test
    void replayDeliversBacklogOnceThenLive() throws InterruptedException {
        AgentActivityHub hub = new AgentActivityHub();
        hub.agentStarted("loan", "c1", "kyc");
        hub.agentCompleted("loan", "c1", "kyc", 10, "PASS", "OK");
        long watermark = hub.currentSeq();

        List<AgentActivityEvent> seen = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(4); // 2 replayed + 2 live
        hub.subscribeWithReplay(e -> {
            seen.add(e);
            latch.countDown();
        }, 0, 50);
        hub.agentStarted("loan", "c2", "credit-risk");
        hub.agentCompleted("loan", "c2", "credit-risk", 20, "WARN", "OK");

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(seen).hasSize(4);
        assertThat(seen.stream().map(AgentActivityEvent::seq)).doesNotHaveDuplicates();
        assertThat(seen.stream().map(AgentActivityEvent::seq).max(Long::compare)).hasValue(watermark + 2);
    }

    @Test
    void replayResumesAfterLastEventId() throws InterruptedException {
        AgentActivityHub hub = new AgentActivityHub();
        hub.agentStarted("loan", "c1", "kyc");
        hub.agentStarted("loan", "c1", "intake");
        long watermark = hub.currentSeq();
        hub.agentStarted("loan", "c1", "decision");

        List<AgentActivityEvent> seen = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        hub.subscribeWithReplay(e -> {
            seen.add(e);
            latch.countDown();
        }, watermark, 50);

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(seen).hasSize(1);
        assertThat(seen.get(0).agent()).isEqualTo("decision");
    }

    @Test
    void caseStateWithoutHubStillWorks() {
        InMemoryCaseState state = new InMemoryCaseState("c1");
        AgentMessage stored = state.post(new AgentMessage(null, null, null, 0, "a", "b",
                AgentMessage.Type.INFO_REQUEST, List.of(), Map.of(), null, 1));
        assertThat(stored.seq()).isEqualTo(1);
        assertThat(state.messages()).hasSize(1);
    }
}
