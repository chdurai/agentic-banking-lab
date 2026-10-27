package io.github.chdurai.agenticbanking.cases;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Minimal forward-only migrator for {@code classpath:db/migration/V<n>__*.sql}. Each script is applied once
 * and recorded in {@code schema_version}; file names are the contract, so Flyway can replace this unchanged.
 */
public final class SchemaMigrator {

    private SchemaMigrator() {
    }

    public static synchronized void migrate(JdbcTemplate jdbc) {
        boolean postgres = Boolean.TRUE.equals(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>)
                c -> c.getMetaData().getDatabaseProductName().equals("PostgreSQL")));
        var tx = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                        java.util.Objects.requireNonNull(jdbc.getDataSource())));
        tx.executeWithoutResult(status -> {
            if (postgres) jdbc.execute("SELECT pg_advisory_xact_lock(376194027)");
            apply(jdbc, postgres);
        });
    }

    private static void apply(JdbcTemplate jdbc, boolean postgres) {
        jdbc.execute("CREATE TABLE IF NOT EXISTS schema_version (script VARCHAR(200) PRIMARY KEY, "
                + "applied_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        try {
            Resource[] scripts = new PathMatchingResourcePatternResolver().getResources("classpath:db/migration/V*.sql");
            Arrays.sort(scripts, Comparator.comparingInt(r -> version(r.getFilename())));
            for (Resource r : scripts) {
                Integer done = jdbc.queryForObject("SELECT COUNT(*) FROM schema_version WHERE script = ?",
                        Integer.class, r.getFilename());
                if (done != null && done > 0) {
                    continue;
                }
                String sql;
                try (var stream = r.getInputStream()) {
                    sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                }
                // Keep historical migration files unchanged while accepting PostgreSQL's large-text spelling.
                if (postgres) sql = sql.replaceAll("\\bCLOB\\b", "TEXT");
                for (String stmt : sql.split(";\\s*\\n")) {
                    if (!stmt.isBlank()) {
                        jdbc.execute(stmt);
                    }
                }
                jdbc.update("INSERT INTO schema_version (script) VALUES (?)", r.getFilename());
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read migrations", e);
        }
    }

    private static int version(String name) {
        return Integer.parseInt(name.substring(1, name.indexOf("__")));
    }
}
