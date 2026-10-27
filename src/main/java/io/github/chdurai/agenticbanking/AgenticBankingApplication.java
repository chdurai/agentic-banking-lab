package io.github.chdurai.agenticbanking;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
// DataSource is created explicitly by PersistenceConfig under the `jdbc` profile,
// so the default demo carries no database.
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
public class AgenticBankingApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgenticBankingApplication.class, args);
    }
}
