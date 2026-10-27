package io.github.chdurai.agenticbanking.grievance;

import io.github.chdurai.agenticbanking.security.CallerContext;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Shared minimal observations; never stores narrative, drafts, rationales or approval details. */
public class JdbcGrievanceInsightsSource {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate independent;

    public JdbcGrievanceInsightsSource(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        independent = new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
    }

    public void record(GrievanceResolution resolution) {
        boolean eligible = GrievanceInsightsAgent.eligible(resolution);
        if (resolution.grievanceId() == null || resolution.grievanceId().isBlank() || resolution.handledAt() == null) {
            throw new IllegalArgumentException("Insights source requires a case identifier and handling time");
        }
        // Independent append keeps an observed sensitive classification excluded even after caller rollback.
        independent.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO grievance_insights_source
                    (observation_id, tenant_id, grievance_id, project_id, handled_at, theme, eligible)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID().toString(), CallerContext.current().tenantId(), resolution.grievanceId(),
                eligible ? resolution.projectId() : "", Timestamp.from(resolution.handledAt()),
                eligible ? resolution.triage().themes().getFirst().name() : "", eligible));
    }

    public List<GrievanceInsightsAgent.ThemeCohort> cohorts(Instant from, Instant until, int minimum) {
        if (!from.isBefore(until) || minimum < 5 || minimum > 500) {
            throw new IllegalArgumentException("Invalid insights source window or privacy floor");
        }
        // Deduplicate across every replica before thresholding; any unsafe observation permanently excludes its case.
        // Conflicting latest timestamps also fail closed instead of choosing a replica's arbitrary theme/project.
        return jdbc.query("""
                WITH latest AS (
                    SELECT grievance_id, MAX(handled_at) AS handled_at
                    FROM grievance_insights_source WHERE tenant_id=? GROUP BY grievance_id
                ), eligible_cases AS (
                    SELECT s.grievance_id, MIN(s.project_id) AS project_id, MIN(s.theme) AS theme
                    FROM grievance_insights_source s
                    JOIN latest l ON l.grievance_id=s.grievance_id AND l.handled_at=s.handled_at
                    WHERE s.tenant_id=? AND s.eligible=TRUE AND s.handled_at>=? AND s.handled_at<?
                      AND NOT EXISTS (
                        SELECT 1 FROM grievance_insights_source unsafe
                        WHERE unsafe.tenant_id=s.tenant_id AND unsafe.grievance_id=s.grievance_id
                          AND unsafe.eligible=FALSE
                      )
                    GROUP BY s.grievance_id
                    HAVING COUNT(DISTINCT s.project_id)=1 AND COUNT(DISTINCT s.theme)=1
                )
                SELECT theme, COUNT(*) AS case_count FROM eligible_cases
                GROUP BY theme HAVING COUNT(*)>=? AND COUNT(DISTINCT project_id)>=? ORDER BY theme
                """, (row, n) -> {
                    GrievanceTriage.Theme theme;
                    try { theme = GrievanceTriage.Theme.valueOf(row.getString("theme")); }
                    catch (IllegalArgumentException e) {
                        throw new DataRetrievalFailureException("Invalid stored insights source theme", e);
                    }
                    if (theme == GrievanceTriage.Theme.CORRUPTION_OR_FRAUD) {
                        throw new DataRetrievalFailureException("Sensitive theme in eligible insights source");
                    }
                    long lower = row.getLong("case_count") / minimum * minimum;
                    try {
                        return new GrievanceInsightsAgent.ThemeCohort(theme, Math.toIntExact(lower),
                                Math.toIntExact(lower + minimum - 1L));
                    } catch (ArithmeticException e) {
                        throw new DataRetrievalFailureException("Insights cohort exceeds supported count range", e);
                    }
                }, CallerContext.current().tenantId(), CallerContext.current().tenantId(),
                Timestamp.from(from), Timestamp.from(until), minimum, minimum);
    }
}
