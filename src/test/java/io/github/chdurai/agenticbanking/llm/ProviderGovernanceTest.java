package io.github.chdurai.agenticbanking.llm;

import io.github.chdurai.agenticbanking.security.CallerProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

class ProviderGovernanceTest {
    private final CallerProperties dev = new CallerProperties(false, Map.of(), Set.of());
    private final LlmProperties approved = LlmProperties.defaults().withProviders(new LlmProperties.Providers(List.of("default")));

    private ProviderGovernance gate(MockEnvironment env, String region, boolean training, boolean retention, String terms, String endpoint) {
        return new ProviderGovernance(approved, dev, env, region, training, retention, terms, endpoint, false);
    }

    @Test void mixedTestAndProductionProfilesCannotBypassReviewOrEndpointApproval() {
        var env = new MockEnvironment().withProperty("spring.ai.model.chat", "openai");
        env.setActiveProfiles("test", "prod");
        assertThatThrownBy(() -> gate(env, "", false, false, "", "https://api.example.com")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> gate(env, "region", true, true, "review-1", "https://api.example.com"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("endpoint");
        env.setProperty("platform.llm.governance.approved-endpoints[0]", "https://api.example.com");
        assertThatCode(() -> gate(env, "region", true, true, "review-1", "https://api.example.com")).doesNotThrowAnyException();
        assertThatThrownBy(() -> gate(env, "region", true, true, "review-1", "http://api.example.com"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void activeAzureAndEmbeddingEndpointsAreCheckedInsteadOfOnlyLogicalProvider() {
        var env = new MockEnvironment().withProperty("spring.ai.model.chat", "azure-openai")
                .withProperty("spring.ai.azure.openai.endpoint", "http://azure.example.com")
                .withProperty("platform.llm.governance.approved-endpoints[0]", "https://api.example.com");
        env.setActiveProfiles("prod");
        assertThatThrownBy(() -> gate(env, "region", true, true, "review-1", "https://api.example.com"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("chat endpoint");
        env.setProperty("spring.ai.azure.openai.endpoint", "https://api.example.com");
        env.setProperty("agents.rag-mode", "vector");
        env.setProperty("spring.ai.model.embedding", "unsupported");
        assertThatThrownBy(() -> gate(env, "region", true, true, "review-1", "https://api.example.com"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("embeddings");
    }

    @Test void outboundMcpRequiresAllowlistedHttpsAndRejectsStdioConfigurationFiles() {
        var env = new MockEnvironment().withProperty("spring.ai.mcp.client.sse.connections.docs.url", "https://tools.example.com")
                .withProperty("spring.ai.mcp.client.stdio.servers-configuration", "file:unreviewed.json");
        var callers = new CallerProperties(false, Map.of(), Set.of("https://tools.example.com"));
        assertThatThrownBy(() -> new ProviderGovernance(approved, callers, env, "", false, false, "",
                "https://api.example.com", true)).isInstanceOf(IllegalStateException.class).hasMessageContaining("Only allow-listed SSE");
    }
}
