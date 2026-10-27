package io.github.chdurai.agenticbanking.grievance;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Code-first insights: no raw narratives or case identifiers enter an LLM or the report.
 * Only fixed, disjoint thematic cohorts from the last completed UTC week can be released.
 */
@Component
@ConditionalOnProperty(prefix = "platform.grievance.insights", name = "enabled", havingValue = "true")
public class GrievanceInsightsAgent {
    public record ThemeCohort(GrievanceTriage.Theme theme, int caseCountLowerBound, int caseCountUpperBound) {}
    public record WeeklyInsights(String schemaVersion, LocalDate weekStarting, LocalDate weekEndingExclusive,
                                 int minimumGroupSize, String sourceScope, List<ThemeCohort> cohorts) {
        public WeeklyInsights {
            if (!"grm-insights-v1".equals(schemaVersion) || weekStarting == null
                    || weekStarting.getDayOfWeek() != DayOfWeek.MONDAY
                    || !weekStarting.plusWeeks(1).equals(weekEndingExclusive)
                    || minimumGroupSize < 5 || minimumGroupSize > 500 || sourceScope == null || sourceScope.isBlank()) {
                throw new IllegalArgumentException("Invalid weekly insights publication");
            }
            cohorts = List.copyOf(cohorts);
            Set<GrievanceTriage.Theme> themes = new HashSet<>();
            for (ThemeCohort cohort : cohorts) {
                if (cohort.theme() == null || cohort.theme() == GrievanceTriage.Theme.CORRUPTION_OR_FRAUD
                        || !themes.add(cohort.theme()) || cohort.caseCountLowerBound() < minimumGroupSize
                        || cohort.caseCountLowerBound() % minimumGroupSize != 0
                        || (long) cohort.caseCountUpperBound() != (long) cohort.caseCountLowerBound() + minimumGroupSize - 1) {
                    throw new IllegalArgumentException("Invalid insights cohort");
                }
            }
        }
    }

    private final GrievanceMemoryStore memory;
    private final GrievanceInsightsProperties properties;
    private final Clock clock;
    private final GrievanceInsightsPublicationStore publications;

    @Autowired
    public GrievanceInsightsAgent(GrievanceMemoryStore memory, GrievanceInsightsProperties properties,
                                 GrievanceInsightsPublicationStore publications) {
        this(memory, properties, Clock.systemUTC(), publications);
    }

    GrievanceInsightsAgent(GrievanceMemoryStore memory, GrievanceInsightsProperties properties, Clock clock) {
        this(memory, properties, clock, new InMemoryGrievanceInsightsPublicationStore());
    }

    GrievanceInsightsAgent(GrievanceMemoryStore memory, GrievanceInsightsProperties properties, Clock clock,
                          GrievanceInsightsPublicationStore publications) {
        this.memory = memory;
        this.properties = properties;
        this.clock = clock;
        this.publications = publications;
    }

    public synchronized WeeklyInsights weekly() {
        LocalDate end = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate start = end.minusWeeks(1);
        WeeklyInsights published = publications.find(start).orElse(null);
        if (published != null) return published;
        Instant from = start.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant until = end.atStartOfDay(ZoneOffset.UTC).toInstant();
        if (memory.insightsSource().isPresent()) {
            var cohorts = memory.insightsSource().orElseThrow().cohorts(from, until, properties.minimumGroupSize());
            return publications.publish(new WeeklyInsights("grm-insights-v1", start, end, properties.minimumGroupSize(),
                    "shared JDBC observations while insights enabled (V7+); triage themes, not investigation ground truth", cohorts));
        }
        Map<String, GrievanceResolution> latest = new HashMap<>();
        Set<String> excluded = new HashSet<>();
        for (GrievanceResolution row : memory.recent(500)) {
            if (!eligible(row)) {
                excluded.add(row.grievanceId());
                continue;
            }
            latest.merge(row.grievanceId(), row,
                    (a, b) -> a.handledAt().isAfter(b.handledAt()) ? a : b);
        }
        Map<GrievanceTriage.Theme, List<GrievanceResolution>> groups = new EnumMap<>(GrievanceTriage.Theme.class);
        for (GrievanceResolution row : latest.values()) {
            if (excluded.contains(row.grievanceId()) || row.handledAt().isBefore(from) || !row.handledAt().isBefore(until)) {
                continue;
            }
            groups.computeIfAbsent(row.triage().themes().getFirst(), key -> new ArrayList<>()).add(row);
        }
        int minimum = properties.minimumGroupSize();
        List<ThemeCohort> cohorts = groups.entrySet().stream()
                .filter(e -> e.getValue().size() >= minimum)
                // A single project must not be inferable as the source of a thematic cohort.
                .filter(e -> e.getValue().stream().map(GrievanceResolution::projectId).distinct().count() >= minimum)
                .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                .map(e -> {
                    int lower = (e.getValue().size() / minimum) * minimum;
                    return new ThemeCohort(e.getKey(), lower, lower + minimum - 1);
                }).toList();
        published = new WeeklyInsights("grm-insights-v1", start, end, minimum,
                "bounded in-memory history (up to 500 records); triage themes, not investigation ground truth", cohorts);
        return publications.publish(published);
    }

    static boolean eligible(GrievanceResolution row) {
        var t = row.triage();
        return row.grievanceId() != null && !row.grievanceId().isBlank()
                && row.projectId() != null && !row.projectId().isBlank() && row.handledAt() != null
                && row.status() == GrievanceResolution.Status.ROUTED
                && row.route() == GrievanceResolution.Route.PROJECT_GRM_OFFICER
                && t != null && !t.retaliationRisk() && !t.vulnerableGroupsAffected()
                && t.confidencePct() >= 60
                && (t.severity() == GrievanceTriage.Severity.LOW || t.severity() == GrievanceTriage.Severity.MODERATE)
                && t.themes() != null && t.themes().size() == 1 && t.themes().getFirst() != null
                && t.themes().getFirst() != GrievanceTriage.Theme.CORRUPTION_OR_FRAUD
                && (row.flags() == null || row.flags().isEmpty());
    }
}
