package io.github.chdurai.agenticbanking.ops;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** Durable event audit store (H2 in-memory for the unit test; Postgres in compose). */
class JdbcEventStoreTest {

    @Test
    void appendsAndReadsBackNewestFirst() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:events" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        JdbcEventStore store = new JdbcEventStore(new JdbcTemplate(ds));

        store.append(PlatformEvent.of("loan.decided", "loan", "A1", "APPROVED"));
        Thread.sleep(2);
        store.append(PlatformEvent.of("grievance.handled", "grievance", "G1", "ROUTED"));

        assertThat(store.count()).isEqualTo(2);
        var recent = store.recent(10);
        assertThat(recent).hasSize(2);
        assertThat(recent.getFirst().type()).isEqualTo("grievance.handled");
        assertThat(recent.getLast().subjectId()).isEqualTo("A1");
        assertThat(recent.getFirst().at()).isNotNull();
    }
}
