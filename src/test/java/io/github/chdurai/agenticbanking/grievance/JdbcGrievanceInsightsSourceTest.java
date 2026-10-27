package io.github.chdurai.agenticbanking.grievance;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chdurai.agenticbanking.cases.SchemaMigrator;
import io.github.chdurai.agenticbanking.security.CallerContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcGrievanceInsightsSourceTest {
    private static final Instant NOW = Instant.parse("2026-06-17T12:00:00Z");
    private static final Instant FROM = Instant.parse("2026-06-08T00:00:00Z");
    private static final Instant UNTIL = Instant.parse("2026-06-15T00:00:00Z");
    private static final Instant HANDLED = FROM.plusSeconds(3600);
    private JdbcTemplate jdbc;
    private JdbcGrievanceInsightsSource source;

    @BeforeEach void database() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
        SchemaMigrator.migrate(jdbc);
        source = new JdbcGrievanceInsightsSource(jdbc);
    }

    static GrievanceResolution row(String id, String project, Instant handled, boolean sensitive) {
        return new GrievanceResolution(id, project, sensitive ? GrievanceResolution.Status.CONFIDENTIAL_ESCALATION
                : GrievanceResolution.Status.ROUTED, sensitive ? GrievanceResolution.Route.CONFIDENTIAL_CASE_TEAM
                : GrievanceResolution.Route.PROJECT_GRM_OFFICER, 168,
                new GrievanceTriage(List.of(GrievanceTriage.Theme.ENVIRONMENTAL_POLLUTION),
                        GrievanceTriage.Severity.MODERATE, GrievanceTriage.Sentiment.CONCERNED,
                        false, false, 90, "PRIVATE_SUMMARY", "PRIVATE_RATIONALE"),
                null, List.of(), "PRIVATE_NARRATIVE", "PRIVATE_APPROVAL", handled);
    }

    private GrievanceInsightsAgent reconstructedAgent() {
        var memory = new GrievanceMemoryStore(Optional.of(new JdbcGrievanceInsightsSource(jdbc)));
        return new GrievanceInsightsAgent(memory, new GrievanceInsightsProperties(true, 5),
                Clock.fixed(NOW, ZoneOffset.UTC),
                new JdbcGrievanceInsightsPublicationStore(jdbc, new ObjectMapper().findAndRegisterModules()));
    }

    @Test void replicaWritesAndConcurrentRetriesDeduplicateBeforeThresholdAndSurviveReconstruction() throws Exception {
        var peer = new JdbcGrievanceInsightsSource(jdbc);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var jobs = java.util.stream.IntStream.range(0, 48)
                    .<java.util.concurrent.Callable<Void>>mapToObj(i -> () -> {
                        (i % 2 == 0 ? source : peer).record(row("CASE-" + i % 6, "PROJECT-" + i % 6, HANDLED, false));
                        return null;
                    }).toList();
            for (var future : executor.invokeAll(jobs)) future.get(10, TimeUnit.SECONDS);
        }
        var report = reconstructedAgent().weekly();
        assertThat(report.cohorts()).containsExactly(new GrievanceInsightsAgent.ThemeCohort(
                GrievanceTriage.Theme.ENVIRONMENTAL_POLLUTION, 5, 9));
        assertThat(report.sourceScope()).contains("shared JDBC");
        for (int i = 6; i < 16; i++) source.record(row("CASE-" + i, "PROJECT-" + i, HANDLED, false));
        assertThat(reconstructedAgent().weekly()).isEqualTo(report);
    }

    @Test void databaseCohortsDoNotTruncateAtTheLocalHistoryLimit() {
        for (int i = 0; i < 510; i++) source.record(row("CASE-" + i, "PROJECT-" + i, HANDLED, false));
        var report = reconstructedAgent().weekly();
        assertThat(report.cohorts()).containsExactly(new GrievanceInsightsAgent.ThemeCohort(
                GrievanceTriage.Theme.ENVIRONMENTAL_POLLUTION, 510, 514));
        assertThat(reconstructedAgent().weekly()).isEqualTo(report);
    }

    @Test void tenantGroupsCannotPoolAndPlatformRoleDoesNotBypassSourceIsolation() {
        for (String tenant : List.of("alpha", "beta")) {
            try (var ignored = new CallerContext(tenant, "operator", Set.of("PLATFORM_ADMIN")).open()) {
                for (int i = 0; i < 3; i++) source.record(row("CASE-" + i, "PROJECT-" + i, HANDLED, false));
                assertThat(source.cohorts(FROM, UNTIL, 5)).isEmpty();
            }
        }
        try (var ignored = new CallerContext("alpha", "operator", Set.of("PLATFORM_ADMIN")).open()) {
            for (int i = 3; i < 5; i++) source.record(row("CASE-" + i, "PROJECT-" + i, HANDLED, false));
            assertThat(source.cohorts(FROM, UNTIL, 5)).hasSize(1);
        }
        try (var ignored = new CallerContext("beta", "operator", Set.of("PLATFORM_ADMIN")).open()) {
            assertThat(source.cohorts(FROM, UNTIL, 5)).isEmpty();
        }
        assertThat(source.cohorts(FROM, UNTIL, 5)).isEmpty();
    }

    @Test void unsafeObservationCannotBeUndoneByOlderArrivalReplayOrCallerRollback() {
        for (int i = 0; i < 5; i++) source.record(row("CASE-" + i, "PROJECT-" + i, HANDLED, false));
        var tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        tx.executeWithoutResult(status -> {
            source.record(row("CASE-0", "PRIVATE_PROJECT", HANDLED.minusSeconds(60), true));
            status.setRollbackOnly();
        });
        source.record(row("CASE-0", "PROJECT-0", HANDLED.plusSeconds(60), false));
        assertThat(new JdbcGrievanceInsightsSource(jdbc).cohorts(FROM, UNTIL, 5)).isEmpty();
        var stored = jdbc.queryForMap("SELECT project_id, theme FROM grievance_insights_source WHERE eligible=FALSE");
        assertThat(stored.get("PROJECT_ID")).isEqualTo("");
        assertThat(stored.get("THEME")).isEqualTo("");
        assertThat(jdbc.queryForList("SELECT * FROM grievance_insights_source").toString())
                .doesNotContain("PRIVATE_");
    }

    @Test void projectPrivacyFloorLatestObservationAndDisjointWindowRemainEnforced() {
        for (int i = 0; i < 5; i++) {
            source.record(row("CASE-" + i, "SAME_PROJECT", HANDLED, false));
            source.record(row("CURRENT-" + i, "PROJECT-" + i, UNTIL, false));
            source.record(row("OLD-" + i, "PROJECT-" + i, FROM.minusSeconds(1), false));
        }
        assertThat(source.cohorts(FROM, UNTIL, 5)).isEmpty();
        for (int i = 0; i < 5; i++) source.record(row("CASE-" + i, "PROJECT-" + i, HANDLED.plusSeconds(1), false));
        assertThat(source.cohorts(FROM, UNTIL, 5)).hasSize(1);
        source.record(row("CASE-0", "PROJECT-0", UNTIL, false));
        assertThat(source.cohorts(FROM, UNTIL, 5)).isEmpty();
    }

    @Test void conflictingLatestReplicaObservationsFailClosedRatherThanChooseAProject() {
        for (int i = 0; i < 5; i++) source.record(row("CASE-" + i, "PROJECT-" + i, HANDLED, false));
        source.record(row("CASE-0", "CONFLICTING_PROJECT", HANDLED, false));
        assertThat(source.cohorts(FROM, UNTIL, 5)).isEmpty();
    }

    @Test void unavailableSourceCannotPublishAnEmptySuccessOrMutateTheLocalHistory() throws Exception {
        var memory = new GrievanceMemoryStore(Optional.of(source));
        jdbc.execute("DROP TABLE grievance_insights_source");
        assertThatThrownBy(() -> memory.record(row("CASE", "PROJECT", HANDLED, false)))
                .isInstanceOf(DataAccessException.class);
        assertThat(memory.total()).isZero();
        assertThatThrownBy(() -> reconstructedAgent().weekly()).isInstanceOf(DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM grievance_insights_publication", Long.class)).isZero();
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(new io.github.chdurai.agenticbanking.controller.GrievanceInsightsController(reconstructedAgent()))
                .setControllerAdvice(new io.github.chdurai.agenticbanking.controller.CaseProblemHandler()).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/grievances/insights/weekly").header("X-Reviewer-Id", "officer")
                        .header("X-Reviewer-Roles", "GRM_OFFICER"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isServiceUnavailable())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.type")
                        .value("urn:agentic-banking:problem:storage-unavailable"));
    }

    @Test void corruptEligibleThemesCannotBePublished() {
        for (int i = 0; i < 5; i++) source.record(row("CASE-" + i, "PROJECT-" + i, HANDLED, false));
        for (String invalid : List.of("UNKNOWN_THEME", "CORRUPTION_OR_FRAUD")) {
            jdbc.update("UPDATE grievance_insights_source SET theme=?", invalid);
            assertThatThrownBy(() -> reconstructedAgent().weekly())
                    .isInstanceOf(org.springframework.dao.DataRetrievalFailureException.class);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM grievance_insights_publication", Long.class)).isZero();
        }
    }

    @Test void committedSnapshotReplaysEvenIfSourceLaterBecomesUnavailable() {
        for (int i = 0; i < 5; i++) source.record(row("CASE-" + i, "PROJECT-" + i, HANDLED, false));
        var published = reconstructedAgent().weekly();
        jdbc.execute("DROP TABLE grievance_insights_source");
        assertThat(reconstructedAgent().weekly()).isEqualTo(published);
    }
}
