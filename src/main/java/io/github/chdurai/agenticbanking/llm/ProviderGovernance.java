package io.github.chdurai.agenticbanking.llm;

import io.github.chdurai.agenticbanking.security.CallerProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/** Configuration records reviewed terms; it cannot certify a provider's actual contractual behavior. */
@Component
public class ProviderGovernance implements LlmCallAdvisor {
    public ProviderGovernance(LlmProperties llm, CallerProperties callers, Environment environment,
                              @Value("${platform.llm.governance.region:}") String region,
                              @Value("${platform.llm.governance.no-training:false}") boolean noTraining,
                              @Value("${platform.llm.governance.retention-reviewed:false}") boolean retention,
                              @Value("${platform.llm.governance.terms-reference:}") String terms,
                              @Value("${spring.ai.openai.base-url:https://api.openai.com}") String baseUrl,
                              @Value("${spring.ai.mcp.client.enabled:false}") boolean mcpEnabled) {
        boolean governed = callers.required() || !callers.credentials().isEmpty()
                || java.util.Arrays.stream(environment.getActiveProfiles()).anyMatch(p ->
                    !java.util.Set.of("dev", "test").contains(p)
                            && !(p.equals("showcase") && io.github.chdurai.agenticbanking.showcase.ShowcaseConfiguration.isLocal(environment)));
        if (governed && (!llm.providers().approved().contains(llm.provider()) || region.isBlank()
                || !noTraining || !retention || terms.isBlank() || !https(baseUrl))) {
            throw new IllegalStateException("Provider requires approval, HTTPS, region, reviewed retention/no-training terms and terms-reference");
        }
        if (governed) {
            var allowed = org.springframework.boot.context.properties.bind.Binder.get(environment)
                    .bind("platform.llm.governance.approved-endpoints",
                            org.springframework.boot.context.properties.bind.Bindable.listOf(String.class))
                    .orElse(java.util.List.of());
            String chat = environment.getProperty("spring.ai.model.chat", "openai");
            String endpoint = switch (chat) {
                case "none" -> null;
                case "openai" -> baseUrl;
                case "azure-openai" -> environment.getProperty("spring.ai.azure.openai.endpoint", "");
                default -> throw new IllegalStateException("Unsupported governed chat backend: " + chat);
            };
            if (endpoint != null && (!https(endpoint) || !allowed.contains(endpoint))) {
                throw new IllegalStateException("Active chat endpoint must be explicitly approved and HTTPS");
            }
            if (environment.getProperty("agents.rag-mode", "keyword").equals("vector")) {
                if (!environment.getProperty("spring.ai.model.embedding", "openai").equals("openai")
                        || !https(baseUrl) || !allowed.contains(baseUrl)) {
                    throw new IllegalStateException("Vector embeddings require an approved HTTPS OpenAI-compatible endpoint");
                }
            }
        }
        if (mcpEnabled && callers.mcpClientAllowlist().isEmpty()) {
            throw new IllegalStateException("MCP clients require an explicit platform.callers.mcp-client-allowlist");
        }
        // Connections are bound at boot by Spring AI; validate all configured client endpoints, not only tool calls.
        if (mcpEnabled) {
            var binder = org.springframework.boot.context.properties.bind.Binder.get(environment);
            var connections = binder.bind("spring.ai.mcp.client.sse.connections",
                    org.springframework.boot.context.properties.bind.Bindable.mapOf(String.class, Connection.class))
                    .orElse(java.util.Map.of());
            if (connections.isEmpty() || connections.values().stream().anyMatch(c -> c.url() == null
                    || !https(c.url()) || !callers.mcpClientAllowlist().contains(c.url())
                    || (c.sseEndpoint() != null && (!c.sseEndpoint().startsWith("/") || c.sseEndpoint().startsWith("//"))))) {
                throw new IllegalStateException("Every MCP client connection must use an allow-listed HTTPS URL");
            }
            if (environment.containsProperty("spring.ai.mcp.client.stdio.servers-configuration")
                    || environment.containsProperty("spring.ai.mcp.client.stdio.connections")
                    || binder.bind("spring.ai.mcp.client.stdio.connections",
                    org.springframework.boot.context.properties.bind.Bindable.mapOf(String.class, Object.class)).isBound()
                    || binder.bind("spring.ai.mcp.client.streamable-http.connections",
                    org.springframework.boot.context.properties.bind.Bindable.mapOf(String.class, Object.class)).isBound()) {
                throw new IllegalStateException("Only allow-listed SSE MCP clients are supported");
            }
        }
    }
    public record Connection(String url, String sseEndpoint) {}

    private static boolean https(String value) {
        try {
            var uri = java.net.URI.create(value);
            return "https".equals(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null
                    && uri.getFragment() == null && uri.getQuery() == null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @Override public String beforeCall(String agentId, String system, String user) { return user; }
}
