package io.github.chdurai.agenticbanking.config;

import io.github.chdurai.agenticbanking.ops.JdbcEventStore;
import io.github.chdurai.agenticbanking.ops.PlatformEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Durable persistence, activated with SPRING_PROFILES_ACTIVE=jdbc.
 * DataSource auto-config is excluded in AgenticBankingApplication so the default demo stays
 * dependency-free; this profile builds the DataSource explicitly from
 * platform.datasource.* (defaults: local H2 file; compose overrides with Postgres).
 */
@Configuration
@ConditionalOnProperty(name = "platform.persistence", havingValue = "jdbc")
public class PersistenceConfig {

    @Bean
    DataSource dataSource(
            @org.springframework.beans.factory.annotation.Value("${platform.datasource.url}") String url,
            @org.springframework.beans.factory.annotation.Value("${platform.datasource.username:sa}") String user,
            @org.springframework.beans.factory.annotation.Value("${platform.datasource.password:}") String pass) {
        return DataSourceBuilder.create().url(url).username(user).password(pass).build();
    }

    @Bean
    JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    io.github.chdurai.agenticbanking.llm.QuotaLimiter sharedTokenQuota(JdbcTemplate jdbcTemplate, io.github.chdurai.agenticbanking.llm.LlmProperties properties) {
        io.github.chdurai.agenticbanking.cases.SchemaMigrator.migrate(jdbcTemplate);
        return new io.github.chdurai.agenticbanking.llm.JdbcTokenQuota(jdbcTemplate, properties);
    }

    @Bean
    io.github.chdurai.agenticbanking.llm.CaseSpendLedger caseSpendLedger(JdbcTemplate jdbcTemplate) {
        io.github.chdurai.agenticbanking.cases.SchemaMigrator.migrate(jdbcTemplate);
        return new io.github.chdurai.agenticbanking.llm.JdbcCaseSpendLedger(jdbcTemplate);
    }

    @Bean
    @ConditionalOnProperty(prefix = "platform.llm.provider-error-budget", name = {"enabled", "shared"}, havingValue = "true")
    io.github.chdurai.agenticbanking.llm.JdbcProviderHealthBudget sharedProviderHealth(
            JdbcTemplate jdbcTemplate, io.github.chdurai.agenticbanking.llm.LlmProperties properties,
            io.github.chdurai.agenticbanking.llm.ProviderErrorBudgetProperties health) {
        io.github.chdurai.agenticbanking.cases.SchemaMigrator.migrate(jdbcTemplate);
        return new io.github.chdurai.agenticbanking.llm.JdbcProviderHealthBudget(jdbcTemplate, properties, health);
    }

    @Bean
    org.springframework.transaction.PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource);
    }

    @Bean
    io.github.chdurai.agenticbanking.cases.CaseStore jdbcCaseStore(JdbcTemplate jdbcTemplate,
                                           org.springframework.transaction.PlatformTransactionManager tm) {
        io.github.chdurai.agenticbanking.cases.SchemaMigrator.migrate(jdbcTemplate);
        return new io.github.chdurai.agenticbanking.cases.JdbcCaseStore(jdbcTemplate,
                new org.springframework.transaction.support.TransactionTemplate(tm));
    }

    @Bean
    io.github.chdurai.agenticbanking.incident.JdbcAlertQueue durableAlertQueue(JdbcTemplate jdbcTemplate,
            org.springframework.transaction.PlatformTransactionManager transactions,
            com.fasterxml.jackson.databind.ObjectMapper json) {
        io.github.chdurai.agenticbanking.cases.SchemaMigrator.migrate(jdbcTemplate);
        return new io.github.chdurai.agenticbanking.incident.JdbcAlertQueue(jdbcTemplate,
                new org.springframework.transaction.support.TransactionTemplate(transactions), json);
    }

    @Bean
    io.github.chdurai.agenticbanking.flow.FlowExecutionJournal flowExecutionJournal(JdbcTemplate jdbcTemplate,
            org.springframework.transaction.PlatformTransactionManager transactions,
            com.fasterxml.jackson.databind.ObjectMapper json) {
        io.github.chdurai.agenticbanking.cases.SchemaMigrator.migrate(jdbcTemplate);
        return new io.github.chdurai.agenticbanking.flow.JdbcFlowExecutionJournal(jdbcTemplate,
                new org.springframework.transaction.support.TransactionTemplate(transactions), json);
    }

    @Bean
    io.github.chdurai.agenticbanking.approval.ApprovalStore jdbcApprovalStore(JdbcTemplate jdbcTemplate) {
        io.github.chdurai.agenticbanking.cases.SchemaMigrator.migrate(jdbcTemplate);
        return new io.github.chdurai.agenticbanking.approval.JdbcApprovalStore(jdbcTemplate);
    }

    @Bean
    @ConditionalOnProperty(prefix = "platform.grievance.insights", name = "enabled", havingValue = "true")
    io.github.chdurai.agenticbanking.grievance.JdbcGrievanceInsightsSource jdbcGrievanceInsightsSource(JdbcTemplate jdbcTemplate) {
        io.github.chdurai.agenticbanking.cases.SchemaMigrator.migrate(jdbcTemplate);
        return new io.github.chdurai.agenticbanking.grievance.JdbcGrievanceInsightsSource(jdbcTemplate);
    }

    @Bean
    io.github.chdurai.agenticbanking.grievance.GrievanceInsightsPublicationStore jdbcInsightsPublicationStore(
            JdbcTemplate jdbcTemplate, com.fasterxml.jackson.databind.ObjectMapper json) {
        io.github.chdurai.agenticbanking.cases.SchemaMigrator.migrate(jdbcTemplate);
        return new io.github.chdurai.agenticbanking.grievance.JdbcGrievanceInsightsPublicationStore(jdbcTemplate, json);
    }

    @Bean
    JdbcEventStore jdbcEventStore(JdbcTemplate jdbcTemplate) {
        return new JdbcEventStore(jdbcTemplate);
    }

    /** Writes every platform event to the durable audit store. */
    @Bean
    JdbcEventAuditListener jdbcEventAuditListener(JdbcEventStore store) {
        return new JdbcEventAuditListener(store);
    }

    /** Event listener that appends each platform event to the JDBC audit store. */
    public static class JdbcEventAuditListener {

        private final JdbcEventStore store;

        public JdbcEventAuditListener(JdbcEventStore store) {
            this.store = store;
        }

        @EventListener
        public void on(PlatformEvent event) {
            store.append(event);
        }
    }
}
