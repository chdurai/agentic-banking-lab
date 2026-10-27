package io.github.chdurai.agenticbanking.integration;

import io.github.chdurai.agenticbanking.cases.CaseService;
import io.github.chdurai.agenticbanking.cases.OutboxRelay;
import io.github.chdurai.agenticbanking.controller.LoanController;
import io.github.chdurai.agenticbanking.model.LoanApplication;
import io.github.chdurai.agenticbanking.model.LoanDecision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "spring.ai.model.chat=none")
@Import(IdempotentLoanApiTest.StubConfig.class)
class IdempotentLoanApiTest {

    @TestConfiguration
    static class StubConfig {
        @Bean
        StubChatModel stubChatModel() {
            return new StubChatModel();
        }
    }

    private static final AtomicInteger IDS = new AtomicInteger();

    @Autowired StubChatModel llm;
    @Autowired LoanController controller;
    @Autowired CaseService cases;
    @Autowired OutboxRelay relay;

    @BeforeEach
    void reset() {
        llm.reset();
    }

    private static LoanApplication app(String id, String amount) {
        return new LoanApplication(id, "Jane Cooper", "4321", new BigDecimal("145000"), 8,
                new BigDecimal(amount), "auto", 60, new BigDecimal("1200"), "Acme", List.of("p.pdf"));
    }

    @Test
    void duplicatePostReturnsSameDecisionWithoutASecondLlmCall() {
        LoanApplication a = app("D" + IDS.incrementAndGet(), "35000");
        var first = controller.evaluate("key-" + a.applicantId(), a);
        int callsAfterFirst = llm.callsTo("decision");
        var second = controller.evaluate("key-" + a.applicantId(), a);

        assertThat(callsAfterFirst).isEqualTo(1);
        assertThat(llm.callsTo("decision")).isEqualTo(1);
        assertThat(second.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(second.getHeaders().getFirst("X-Case-Id")).isEqualTo(first.getHeaders().getFirst("X-Case-Id"));
        assertThat(second.getBody().decision()).isEqualTo(first.getBody().decision());
        assertThat(second.getBody().interestRateApr()).isEqualByComparingTo(first.getBody().interestRateApr());
    }

    @Test
    void sameKeyDifferentBodyIs409() {
        LoanApplication a = app("D" + IDS.incrementAndGet(), "35000");
        controller.evaluate("k-" + a.applicantId(), a);

        assertThatThrownBy(() -> controller.evaluate("k-" + a.applicantId(), app(a.applicantId(), "40000")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(409));
    }

    @Test
    void relayFailureNeverChangesTheStoredDecision() {
        LoanApplication a = app("D" + IDS.incrementAndGet(), "35000");
        var out = controller.evaluate("r-" + a.applicantId(), a);
        String caseId = out.getHeaders().getFirst("X-Case-Id");

        assertThat(cases.store().pendingOutbox(100)).anyMatch(m -> m.caseId().equals(caseId));
        relay.relayPending();
        assertThat(cases.store().pendingOutbox(100)).noneMatch(m -> m.caseId().equals(caseId));
        assertThat(cases.store().find(caseId).orElseThrow().resultJson()).contains("APPROVED");
    }

    @Test
    void failedWorkIsRetriedUnderTheSameKey() {
        assertThatThrownBy(() -> cases.runIdempotent("loan", "boom", "s", "body", String.class, "x",
                () -> { throw new IllegalStateException("boom"); }))
                .isInstanceOf(IllegalStateException.class);
        var retry = cases.runIdempotent("loan", "boom", "s", "body", String.class, "x", () -> "ok");
        assertThat(retry.result()).isEqualTo("ok");
        assertThat(cases.runIdempotent("loan", "boom", "s", "body", String.class, "x", () -> "never").replayed()).isTrue();
    }
}
