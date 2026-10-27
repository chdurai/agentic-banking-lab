package io.github.chdurai.agenticbanking.integration;

import io.github.chdurai.agenticbanking.llm.JdbcProviderHealthBudget;
import io.github.chdurai.agenticbanking.llm.LlmException;
import io.github.chdurai.agenticbanking.llm.LlmProperties;
import io.github.chdurai.agenticbanking.llm.ProviderErrorBudgetProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.*;

/** Runs the same four real flow degradation paths with actual shared-health Spring/JDBC wiring. */
@SpringBootTest(properties = {
        "spring.ai.model.chat=none", "spring.ai.model.embedding=none",
        "spring.ai.mcp.client.enabled=false", "spring.ai.mcp.server.enabled=false",
        "agents.anomaly.enabled=false", "platform.cases.worker-enabled=false",
        "platform.persistence=jdbc", "platform.datasource.url=jdbc:h2:mem:shared-health-flows;DB_CLOSE_DELAY=-1",
        "platform.llm.provider-error-budget.enabled=true", "platform.llm.provider-error-budget.shared=true",
        "platform.llm.provider-error-budget.sliding-window-size=2",
        "platform.llm.provider-error-budget.minimum-calls=2",
        "platform.llm.provider-error-budget.wait-open-ms=60000",
        "platform.llm.resilience.retry.initial-backoff-ms=1", "platform.llm.resilience.retry.jitter=0"
})
@Import(ProviderErrorBudgetFlowTest.Config.class)
class JdbcSharedProviderHealthFlowTest extends ProviderErrorBudgetFlowTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired LlmProperties properties;
    @Autowired ProviderErrorBudgetProperties health;

    @Override @Test
    void exhaustedProviderBudgetDegradesAllFourFlowsWithoutAdditionalProviderCalls() {
        super.exhaustedProviderBudgetDegradesAllFourFlowsWithoutAdditionalProviderCalls();
        var reconstructed = new JdbcProviderHealthBudget(jdbc, properties, health);
        assertThat(reconstructed.snapshot()).containsEntry("shared", true).containsEntry("state", "OPEN");
        assertThatThrownBy(() -> reconstructed.execute(() -> { throw new AssertionError("A peer must not dispatch"); }))
                .isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.kind()).isEqualTo(LlmException.Kind.PROVIDER_BUDGET));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM provider_health_sample", Integer.class)).isEqualTo(2);
        assertThat(model.attempts).hasValue(2);
    }
}
