package io.github.chdurai.agenticbanking.grievance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chdurai.agenticbanking.security.CallerContext;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Date;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

public class JdbcGrievanceInsightsPublicationStore implements GrievanceInsightsPublicationStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate independent;

    public JdbcGrievanceInsightsPublicationStore(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
        independent = new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
    }

    @Override public Optional<GrievanceInsightsAgent.WeeklyInsights> find(LocalDate weekStarting) {
        return jdbc.query("SELECT payload_json FROM grievance_insights_publication WHERE tenant_id=? AND week_start=?",
                (row, n) -> {
                    var report = decode(row.getString(1));
                    if (!report.weekStarting().equals(weekStarting)) {
                        throw new DataRetrievalFailureException("Stored publication week does not match its key");
                    }
                    return report;
                }, CallerContext.current().tenantId(), Date.valueOf(weekStarting))
                .stream().findFirst();
    }

    @Override public GrievanceInsightsAgent.WeeklyInsights publish(GrievanceInsightsAgent.WeeklyInsights candidate) {
        String payload;
        try { payload = json.writeValueAsString(candidate); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Cannot serialize insights publication", e); }
        // Autocommit isolates uniqueness races from ambient PostgreSQL transactions and caller rollback.
        return Objects.requireNonNull(independent.execute(status -> {
            try {
                jdbc.update("INSERT INTO grievance_insights_publication (tenant_id, week_start, payload_json) VALUES (?, ?, ?)",
                        CallerContext.current().tenantId(), Date.valueOf(candidate.weekStarting()), payload);
            } catch (DuplicateKeyException alreadyPublished) {
                // Return the first committed snapshot, never replace it with a recomputed cohort.
            }
            return find(candidate.weekStarting())
                    .orElseThrow(() -> new DataRetrievalFailureException("Committed insights publication unavailable"));
        }));
    }

    private GrievanceInsightsAgent.WeeklyInsights decode(String payload) {
        try { return json.readValue(payload, GrievanceInsightsAgent.WeeklyInsights.class); }
        catch (JsonProcessingException e) { throw new DataRetrievalFailureException("Invalid stored insights publication", e); }
    }
}
