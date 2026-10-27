package io.github.chdurai.agenticbanking.grievance;

import java.time.LocalDate;
import java.util.Optional;

/** Tenant-scoped, first-publication-wins snapshots; publication never updates a released week. */
public interface GrievanceInsightsPublicationStore {
    Optional<GrievanceInsightsAgent.WeeklyInsights> find(LocalDate weekStarting);
    GrievanceInsightsAgent.WeeklyInsights publish(GrievanceInsightsAgent.WeeklyInsights candidate);
}
