package io.github.chdurai.agenticbanking.config;

import io.github.chdurai.agenticbanking.llm.LlmProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Agent configuration. The ChatClient.Builder comes from Spring AI auto-configuration (so
 * observability and customizers apply); all calls go through {@code io.github.chdurai.agenticbanking.llm.LlmGateway}.
 */
@Configuration
@EnableConfigurationProperties({AgentProperties.class, LlmProperties.class, io.github.chdurai.agenticbanking.policy.LoanPolicyProperties.class,
        io.github.chdurai.agenticbanking.security.CallerProperties.class,
        io.github.chdurai.agenticbanking.llm.ProviderErrorBudgetProperties.class,
        io.github.chdurai.agenticbanking.approval.HitlProperties.class, io.github.chdurai.agenticbanking.flow.FlowProperties.class,
        io.github.chdurai.agenticbanking.incident.IncidentPolicyProperties.class, io.github.chdurai.agenticbanking.incident.IncidentAutonomyProperties.class,
        io.github.chdurai.agenticbanking.incident.AlertIngestionProperties.class, io.github.chdurai.agenticbanking.grievance.GrievanceInsightsProperties.class,
        io.github.chdurai.agenticbanking.capability.CapabilityProperties.class, io.github.chdurai.agenticbanking.memory.RetentionProperties.class})
public class AgentConfig {
}
