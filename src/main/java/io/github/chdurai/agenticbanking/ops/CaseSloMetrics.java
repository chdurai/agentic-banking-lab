package io.github.chdurai.agenticbanking.ops;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.github.chdurai.agenticbanking.cases.CaseRecord;
import io.github.chdurai.agenticbanking.cases.CaseStore;
import io.github.chdurai.agenticbanking.security.CallerProperties;
import io.github.chdurai.agenticbanking.security.TenantMaintenance;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

@Component
public class CaseSloMetrics {
    private static final Map<String, Duration> TARGETS = Map.of(
            "loan", Duration.ofSeconds(20), "incident", Duration.ofSeconds(30),
            "grievance", Duration.ofSeconds(10), "document", Duration.ofSeconds(30));
    private final MeterRegistry registry;
    private final CaseStore cases;
    private final CallerProperties callers;

    public CaseSloMetrics(MeterRegistry registry, CaseStore cases, CallerProperties callers) {
        this.registry = registry;
        this.cases = cases;
        this.callers = callers;
        Gauge.builder("case.queue.depth", this, CaseSloMetrics::queued).strongReference(true).register(registry);
    }

    /** Creation to initial disposition, including durable queue time, not subsequent human-review time. */
    public void processed(CaseRecord row) {
        Duration target = TARGETS.get(row.flow());
        if (target == null) throw new IllegalArgumentException("Unknown flow for case SLO");
        Duration elapsed = Duration.between(row.createdAt(), row.updatedAt());
        Timer.builder("case.processing").tag("flow", row.flow()).tag("status", row.status().name())
                .serviceLevelObjectives(target).publishPercentileHistogram().register(registry)
                .record(elapsed.isNegative() ? Duration.ZERO : elapsed);
    }

    private double queued() {
        long total = 0;
        for (var caller : TenantMaintenance.scopes(callers)) {
            try (var ignored = caller.open()) {
                total = Math.addExact(total, cases.countQueued());
            }
        }
        return total;
    }
}
