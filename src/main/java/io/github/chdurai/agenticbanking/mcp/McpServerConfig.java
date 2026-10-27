package io.github.chdurai.agenticbanking.mcp;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Configuration
@org.springframework.boot.autoconfigure.EnableAutoConfiguration(
        exclude = org.springframework.ai.mcp.server.autoconfigure.McpWebMvcServerAutoConfiguration.class)
public class McpServerConfig {
    @jakarta.annotation.PostConstruct
    void propagateCaller() { io.github.chdurai.agenticbanking.security.CallerContext.enableReactorPropagation(); }

    @Bean
    static org.springframework.beans.factory.config.BeanFactoryPostProcessor governMcpClientInitialization() {
        return factory -> {
            for (String name : factory.getBeanDefinitionNames()) {
                var definition = factory.getBeanDefinition(name);
                String source = definition.getFactoryBeanName();
                if (source != null && source.startsWith("org.springframework.ai.mcp.client.")) {
                    var dependencies = new java.util.LinkedHashSet<String>();
                    if (definition.getDependsOn() != null) dependencies.addAll(java.util.List.of(definition.getDependsOn()));
                    dependencies.add("providerGovernance");
                    definition.setDependsOn(dependencies.toArray(String[]::new));
                }
            }
        };
    }

    @Bean
    @ConditionalOnProperty(name = "spring.ai.mcp.server.enabled", havingValue = "true", matchIfMissing = true)
    ScopedMcpTransport scopedMcpTransport(com.fasterxml.jackson.databind.ObjectMapper json,
                                          org.springframework.core.env.Environment env) {
        return new ScopedMcpTransport(json, env.getProperty("spring.ai.mcp.server.sse-endpoint", "/sse"),
                env.getProperty("spring.ai.mcp.server.sse-message-endpoint", "/mcp/message"));
    }

    @Bean
    @ConditionalOnProperty(name = "spring.ai.mcp.server.enabled", havingValue = "true", matchIfMissing = true)
    org.springframework.web.servlet.function.RouterFunction<org.springframework.web.servlet.function.ServerResponse>
    scopedMcpRoutes(ScopedMcpTransport transport) { return transport.routes(); }

    @Bean
    ToolCallbackProvider platformTools(PlatformMcpTools tools) {
        return MethodToolCallbackProvider.builder().toolObjects(tools).build();
    }
}
