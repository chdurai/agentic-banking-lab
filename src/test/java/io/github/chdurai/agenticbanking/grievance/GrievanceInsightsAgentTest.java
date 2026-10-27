package io.github.chdurai.agenticbanking.grievance;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chdurai.agenticbanking.controller.GrievanceInsightsController;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GrievanceInsightsAgentTest {
    private static final Instant NOW = Instant.parse("2026-06-17T12:00:00Z");
    private static final Instant LAST_WEEK = Instant.parse("2026-06-10T12:00:00Z");
    private final GrievanceMemoryStore memory = new GrievanceMemoryStore();

    private GrievanceInsightsAgent agent(int minimum) {
        return new GrievanceInsightsAgent(memory, new GrievanceInsightsProperties(true, minimum),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private GrievanceResolution row(String id, String project, GrievanceTriage.Theme theme, Instant handledAt,
                                    GrievanceResolution.Status status) {
        return new GrievanceResolution(id, project, status,
                status == GrievanceResolution.Status.CONFIDENTIAL_ESCALATION
                        ? GrievanceResolution.Route.CONFIDENTIAL_CASE_TEAM : GrievanceResolution.Route.PROJECT_GRM_OFFICER,
                168, new GrievanceTriage(List.of(theme), GrievanceTriage.Severity.MODERATE,
                GrievanceTriage.Sentiment.CONCERNED, false, false, 90,
                "PRIVATE_SUMMARY", "PRIVATE_RATIONALE"), null, List.of(),
                "PRIVATE_NARRATIVE", "PRIVATE_APPROVAL", handledAt);
    }

    private void add(int count, GrievanceTriage.Theme theme) {
        for (int i = 0; i < count; i++) {
            memory.record(row("CASE-" + theme + "-" + i, "PROJECT-" + i, theme, LAST_WEEK,
                    GrievanceResolution.Status.ROUTED));
        }
    }

    @Test
    void fewerThanMinimumCasesRevealNeitherCohortNorSuppressedCount() throws Exception {
        add(4, GrievanceTriage.Theme.ENVIRONMENTAL_POLLUTION);
        var report = agent(5).weekly();
        assertThat(report.cohorts()).isEmpty();
        assertThat(new ObjectMapper().findAndRegisterModules().writeValueAsString(report))
                .doesNotContain("ENVIRONMENTAL_POLLUTION", "suppressed", "PROJECT-", "CASE-", "PRIVATE_");
    }

    @Test
    void exactMinimumReleasesOnlyTypedThemeAndRoundedCountBand() throws Exception {
        add(7, GrievanceTriage.Theme.LABOR_AND_WORKING_CONDITIONS);
        var report = agent(5).weekly();
        assertThat(report.weekStarting()).isEqualTo(LocalDate.parse("2026-06-08"));
        assertThat(report.weekEndingExclusive()).isEqualTo(LocalDate.parse("2026-06-15"));
        assertThat(report.cohorts()).containsExactly(new GrievanceInsightsAgent.ThemeCohort(
                GrievanceTriage.Theme.LABOR_AND_WORKING_CONDITIONS, 5, 9));
        assertThat(new ObjectMapper().findAndRegisterModules().writeValueAsString(report))
                .doesNotContain("PRIVATE_", "CASE-", "PROJECT-");
    }

    @Test
    void independentSmallThemesCannotPoolToReachThreshold() {
        add(4, GrievanceTriage.Theme.ENVIRONMENTAL_POLLUTION);
        add(4, GrievanceTriage.Theme.LABOR_AND_WORKING_CONDITIONS);
        assertThat(agent(5).weekly().cohorts()).isEmpty();
    }

    @Test
    void duplicateCasesCannotInflateCohortSize() {
        for (int i = 0; i < 50; i++) {
            memory.record(row("SAME_CASE", "PROJECT-" + i, GrievanceTriage.Theme.ENVIRONMENTAL_POLLUTION,
                    LAST_WEEK.plusSeconds(i), GrievanceResolution.Status.ROUTED));
        }
        assertThat(agent(5).weekly().cohorts()).isEmpty();
    }

    @Test
    void singleProjectCannotBecomeIdentifiableFromManyCases() {
        for (int i = 0; i < 20; i++) {
            memory.record(row("CASE-" + i, "SAME_PROJECT", GrievanceTriage.Theme.ENVIRONMENTAL_POLLUTION,
                    LAST_WEEK, GrievanceResolution.Status.ROUTED));
        }
        assertThat(agent(5).weekly().cohorts()).isEmpty();
    }

    @Test
    void sensitiveOrPendingCasesNeverContributeEvenWithOlderEligibleRecord() {
        add(5, GrievanceTriage.Theme.ENVIRONMENTAL_POLLUTION);
        for (int i = 0; i < 5; i++) {
            memory.record(row("CASE-ENVIRONMENTAL_POLLUTION-" + i, "PROJECT-" + i,
                    GrievanceTriage.Theme.ENVIRONMENTAL_POLLUTION, LAST_WEEK.plusSeconds(1),
                    GrievanceResolution.Status.CONFIDENTIAL_ESCALATION));
        }
        assertThat(agent(5).weekly().cohorts()).isEmpty();
        add(5, GrievanceTriage.Theme.CORRUPTION_OR_FRAUD);
        assertThat(agent(5).weekly().cohorts()).isEmpty();
    }

    @Test
    void noRollingOrCallerChosenWindowCanRevealCurrentWeekOrOldCases() {
        for (int i = 0; i < 10; i++) {
            memory.record(row("CURRENT-" + i, "PROJECT-" + i, GrievanceTriage.Theme.ENVIRONMENTAL_POLLUTION,
                    NOW, GrievanceResolution.Status.ROUTED));
            memory.record(row("OLD-" + i, "PROJECT-" + i, GrievanceTriage.Theme.LABOR_AND_WORKING_CONDITIONS,
                    LAST_WEEK.minusSeconds(14 * 86400), GrievanceResolution.Status.ROUTED));
        }
        assertThat(agent(5).weekly().cohorts()).isEmpty();
    }

    @Test
    void repeatedQueriesDoNotRevealIncrementalChangesWithinAReleasedWeek() {
        add(5, GrievanceTriage.Theme.ENVIRONMENTAL_POLLUTION);
        var insights = agent(5);
        var published = insights.weekly();
        add(20, GrievanceTriage.Theme.ENVIRONMENTAL_POLLUTION);
        assertThat(insights.weekly()).isSameAs(published);
    }

    @Test
    void privacyFloorCannotBeDisabledByConfiguration() {
        assertThatThrownBy(() -> new GrievanceInsightsProperties(true, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GrievanceInsightsProperties(true, 501))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tenantHistoriesAndFrozenReportsCannotPoolOrLeakAcrossTenants() {
        var insights = agent(5);
        try (var ignored = new io.github.chdurai.agenticbanking.security.CallerContext("alpha", "officer", java.util.Set.of("GRM_OFFICER")).open()) {
            add(5, GrievanceTriage.Theme.ENVIRONMENTAL_POLLUTION);
            assertThat(insights.weekly().cohorts()).hasSize(1);
        }
        try (var ignored = new io.github.chdurai.agenticbanking.security.CallerContext("beta", "officer", java.util.Set.of("GRM_OFFICER")).open()) {
            add(4, GrievanceTriage.Theme.ENVIRONMENTAL_POLLUTION);
            assertThat(insights.weekly().cohorts()).isEmpty();
        }
        assertThat(insights.weekly().cohorts()).isEmpty();
    }

    @Test
    void endpointRequiresTrustedAuthenticatedOfficerHeaders() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new GrievanceInsightsController(agent(5))).build();
        mvc.perform(get("/api/grievances/insights/weekly")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/grievances/insights/weekly").header("X-Reviewer-Id", "reviewer")
                .header("X-Reviewer-Roles", "DOCUMENT_REVIEWER")).andExpect(status().isForbidden());
        mvc.perform(get("/api/grievances/insights/weekly").header("X-Reviewer-Id", "reviewer")
                .header("X-Reviewer-Roles", "GRM_OFFICER")).andExpect(status().isOk());
    }
}
