package io.github.chdurai.agenticbanking.grievance;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chdurai.agenticbanking.cases.SchemaMigrator;
import io.github.chdurai.agenticbanking.controller.CaseProblemHandler;
import io.github.chdurai.agenticbanking.controller.GrievanceInsightsController;
import io.github.chdurai.agenticbanking.security.CallerContext;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class GrievanceInsightsPublicationStoreTest {
    enum Impl { MEMORY, JDBC }
    private static final LocalDate WEEK = LocalDate.parse("2026-06-08");
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules()
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private JdbcTemplate database() {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:insights" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        var jdbc = new JdbcTemplate(ds);
        SchemaMigrator.migrate(jdbc);
        return jdbc;
    }

    private GrievanceInsightsPublicationStore store(Impl impl) {
        return impl == Impl.MEMORY ? new InMemoryGrievanceInsightsPublicationStore()
                : new JdbcGrievanceInsightsPublicationStore(database(), json);
    }

    private GrievanceInsightsAgent.WeeklyInsights report(int lower) {
        return new GrievanceInsightsAgent.WeeklyInsights("grm-insights-v1", WEEK, WEEK.plusWeeks(1), 5,
                "bounded tenant-scoped history; not investigation ground truth", List.of(
                new GrievanceInsightsAgent.ThemeCohort(GrievanceTriage.Theme.ENVIRONMENTAL_POLLUTION, lower, lower + 4)));
    }

    @ParameterizedTest @EnumSource(Impl.class)
    void firstPublicationIsImmutableAndIsolatedByTenant(Impl impl) {
        var store = store(impl);
        for (String tenant : List.of("alpha", "beta")) {
            try (var ignored = new CallerContext(tenant, "evaluator", Set.of("GRM_OFFICER")).open()) {
                assertThat(store.find(WEEK)).isEmpty();
                var first = report(tenant.equals("alpha") ? 5 : 10);
                assertThat(store.publish(first)).isEqualTo(first);
                assertThat(store.publish(report(20))).isEqualTo(first);
                assertThat(store.find(WEEK)).contains(first);
            }
        }
        assertThat(store.find(WEEK)).isEmpty();
    }

    @Test void racingReplicasReturnTheSameCommittedSnapshotAfterReconstruction() throws Exception {
        var jdbc = database();
        var first = new JdbcGrievanceInsightsPublicationStore(jdbc, json);
        var second = new JdbcGrievanceInsightsPublicationStore(jdbc, json);
        List<GrievanceInsightsAgent.WeeklyInsights> results = new ArrayList<>();
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<GrievanceInsightsAgent.WeeklyInsights>> calls = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                int index = i;
                calls.add(() -> {
                    try (var ignored = new CallerContext("alpha", "evaluator", Set.of("GRM_OFFICER")).open()) {
                        return (index % 2 == 0 ? first : second).publish(report(index % 2 == 0 ? 5 : 10));
                    }
                });
            }
            for (var result : workers.invokeAll(calls)) results.add(result.get());
        }
        assertThat(results).hasSize(24).allMatch(results.getFirst()::equals);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM grievance_insights_publication", Long.class)).isEqualTo(1);
        try (var ignored = new CallerContext("alpha", "evaluator", Set.of("GRM_OFFICER")).open()) {
            assertThat(new JdbcGrievanceInsightsPublicationStore(jdbc, json).find(WEEK)).contains(results.getFirst());
        }
    }

    @Test void ambientRollbackCannotUnpublishAnAlreadyReturnedSnapshot() {
        var jdbc = database();
        var store = new JdbcGrievanceInsightsPublicationStore(jdbc, json);
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        transaction.executeWithoutResult(status -> {
            assertThat(store.publish(report(5))).isEqualTo(report(5));
            assertThat(store.publish(report(10))).isEqualTo(report(5));
            status.setRollbackOnly();
        });
        assertThat(new JdbcGrievanceInsightsPublicationStore(jdbc, json).find(WEEK)).contains(report(5));
    }

    @Test void serviceReconstructionCannotReplaceAPublishedWeekWithAnEmptyHistory() {
        var jdbc = database();
        var first = new JdbcGrievanceInsightsPublicationStore(jdbc, json);
        first.publish(report(5));
        var reconstructed = new GrievanceInsightsAgent(new GrievanceMemoryStore(), new GrievanceInsightsProperties(true, 5),
                Clock.fixed(Instant.parse("2026-06-17T12:00:00Z"), ZoneOffset.UTC),
                new JdbcGrievanceInsightsPublicationStore(jdbc, json));
        assertThat(reconstructed.weekly()).isEqualTo(report(5));
    }

    @Test void corruptedOrMismatchedStoredPublicationsFailClosed() throws Exception {
        var jdbc = database();
        var store = new JdbcGrievanceInsightsPublicationStore(jdbc, json);
        store.publish(report(5));
        String valid = json.writeValueAsString(report(5));
        for (String corrupt : List.of("invalid-json", valid.replace("2026-06-08", "2026-06-01")
                .replace("2026-06-15", "2026-06-08"), valid.replace("\"caseCountLowerBound\":5", "\"caseCountLowerBound\":1"))) {
            jdbc.update("UPDATE grievance_insights_publication SET payload_json=?", corrupt);
            assertThatThrownBy(() -> store.find(WEEK)).isInstanceOf(DataRetrievalFailureException.class);
        }
    }

    @Test void unavailablePublicationStorageReturnsExplicit503RatherThanRecomputedInsights() throws Exception {
        var store = mock(GrievanceInsightsPublicationStore.class);
        when(store.find(any())).thenThrow(new DataAccessResourceFailureException("synthetic outage"));
        var agent = new GrievanceInsightsAgent(new GrievanceMemoryStore(), new GrievanceInsightsProperties(true, 5),
                Clock.fixed(Instant.parse("2026-06-17T12:00:00Z"), ZoneOffset.UTC), store);
        var mvc = MockMvcBuilders.standaloneSetup(new GrievanceInsightsController(agent))
                .setControllerAdvice(new CaseProblemHandler()).build();
        mvc.perform(get("/api/grievances/insights/weekly").header("X-Reviewer-Id", "officer")
                        .header("X-Reviewer-Roles", "GRM_OFFICER"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("urn:agentic-banking:problem:storage-unavailable"));
        verify(store, never()).publish(any());
    }
}
