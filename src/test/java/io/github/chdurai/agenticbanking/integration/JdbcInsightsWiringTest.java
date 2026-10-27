package io.github.chdurai.agenticbanking.integration;

import io.github.chdurai.agenticbanking.grievance.GrievanceInsightsPublicationStore;
import io.github.chdurai.agenticbanking.grievance.JdbcGrievanceInsightsPublicationStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.ai.model.chat=none", "spring.ai.model.embedding=none",
        "spring.ai.mcp.client.enabled=false", "spring.ai.mcp.server.enabled=false",
        "agents.anomaly.enabled=false", "platform.cases.worker-enabled=false",
        "platform.persistence=jdbc", "platform.datasource.url=jdbc:h2:mem:insights-wiring;DB_CLOSE_DELAY=-1",
        "platform.grievance.insights.enabled=true"
})
@AutoConfigureMockMvc
@Import(AgentFlowsStubbedTest.StubConfig.class)
class JdbcInsightsWiringTest {
    @Autowired GrievanceInsightsPublicationStore publications;
    @Autowired JdbcTemplate jdbc;
    @Autowired io.github.chdurai.agenticbanking.grievance.GrievanceMemoryStore memory;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;

    @Test void jdbcModeMigratesAndPublishesThroughTheConfiguredDurableStore() throws Exception {
        assertThat(publications).isInstanceOf(JdbcGrievanceInsightsPublicationStore.class);
        var lastWeek = java.time.LocalDate.now(java.time.ZoneOffset.UTC)
                .with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
                .minusWeeks(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        for (int i = 0; i < 5; i++) {
            memory.record(new io.github.chdurai.agenticbanking.grievance.GrievanceResolution("CASE-" + i, "PROJECT-" + i,
                    io.github.chdurai.agenticbanking.grievance.GrievanceResolution.Status.ROUTED,
                    io.github.chdurai.agenticbanking.grievance.GrievanceResolution.Route.PROJECT_GRM_OFFICER, 168,
                    new io.github.chdurai.agenticbanking.grievance.GrievanceTriage(
                            java.util.List.of(io.github.chdurai.agenticbanking.grievance.GrievanceTriage.Theme.ENVIRONMENTAL_POLLUTION),
                            io.github.chdurai.agenticbanking.grievance.GrievanceTriage.Severity.MODERATE,
                            io.github.chdurai.agenticbanking.grievance.GrievanceTriage.Sentiment.CONCERNED,
                            false, false, 90, "PRIVATE_SUMMARY", "PRIVATE_RATIONALE"),
                    null, java.util.List.of(), "PRIVATE_NARRATIVE", null, lastWeek));
        }
        String first = mvc.perform(get("/api/grievances/insights/weekly")).andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value("grm-insights-v1"))
                .andExpect(jsonPath("$.cohorts[0].caseCountLowerBound").value(5))
                .andExpect(jsonPath("$.sourceScope").value(org.hamcrest.Matchers.containsString("shared JDBC")))
                .andReturn().getResponse().getContentAsString();
        assertThat(first).doesNotContain("PRIVATE_", "CASE-", "PROJECT-");
        String replay = mvc.perform(get("/api/grievances/insights/weekly")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(replay).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM grievance_insights_publication", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM schema_version WHERE script='V6__insights_publication.sql'",
                Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM schema_version WHERE script='V7__grievance_insights_source.sql'",
                Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM grievance_insights_source", Long.class)).isEqualTo(5);
    }
}
