package io.github.chdurai.agenticbanking.config;

import io.github.chdurai.agenticbanking.approval.SimulatedLoanBooking;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
@ConditionalOnProperty(name = "platform.loan.recovery-simulation", havingValue = "true")
public class LoanRecoveryConfiguration {
    @Bean
    SimulatedLoanBooking simulatedLoanBooking(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        return new SimulatedLoanBooking(jdbc, new TransactionTemplate(transactions));
    }
}
