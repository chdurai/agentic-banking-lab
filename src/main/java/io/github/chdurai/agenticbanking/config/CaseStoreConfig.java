package io.github.chdurai.agenticbanking.config;

import io.github.chdurai.agenticbanking.cases.CaseStore;
import io.github.chdurai.agenticbanking.cases.InMemoryCaseStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Default (non-JDBC) case store. JDBC is registered by {@link PersistenceConfig}. */
@Configuration
public class CaseStoreConfig {

    @Bean
    @ConditionalOnProperty(name = "platform.persistence", havingValue = "memory", matchIfMissing = true)
    CaseStore inMemoryCaseStore() {
        return new InMemoryCaseStore();
    }

    @Bean
    @ConditionalOnProperty(name = "platform.persistence", havingValue = "memory", matchIfMissing = true)
    io.github.chdurai.agenticbanking.approval.ApprovalStore inMemoryApprovalStore() {
        return new io.github.chdurai.agenticbanking.approval.InMemoryApprovalStore();
    }
}
