package io.github.chdurai.agenticbanking.investigation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chdurai.agenticbanking.capability.CapabilityGuard;
import io.github.chdurai.agenticbanking.llm.LlmGateway;
import io.github.chdurai.agenticbanking.ops.PlatformEventPublisher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "platform.loan.investigation.enabled", havingValue = "true")
public class InvestigationConfiguration {
    @Bean
    InvestigationStore investigationStore(JdbcTemplate jdbc) { return new InvestigationStore(jdbc); }
    @Bean
    DecisionLaboratory decisionLaboratory(InvestigationStore store, ObjectMapper json) {
        return new DecisionLaboratory(store, json);
    }
    @Bean
    ReviewOutcomeReader reviewOutcomeReader(InvestigationStore store, JdbcTemplate jdbc, ObjectMapper json) {
        return new ReviewOutcomeReader(store, jdbc, json);
    }

    @Bean
    LoanEvidenceTools loanEvidenceTools(CapabilityGuard capabilities) { return new LoanEvidenceTools(capabilities); }

    @Bean
    LoanInvestigator loanInvestigator(LlmGateway gateway, CapabilityGuard capabilities, InvestigationStore store,
                                      PlatformEventPublisher events, ObjectMapper json, LoanEvidenceTools tools,
                                      io.github.chdurai.agenticbanking.llm.LlmProperties properties, io.github.chdurai.agenticbanking.ops.AgentActivityHub activity,
                                      io.micrometer.core.instrument.MeterRegistry metrics) {
        var budget = properties.budget().newCaseBudget("loan");
        if (!budget.usageLimited() || !properties.provider().equals("scripted-showcase") && !budget.costLimited())
            throw new IllegalStateException("Investigation requires configured loan token limits and, for live providers, estimated cost limits");
        return new LoanInvestigator(gateway, capabilities, store, events, json, tools, activity, metrics);
    }
}
