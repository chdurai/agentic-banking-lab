package io.github.chdurai.agenticbanking.ops;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.github.chdurai.agenticbanking.incident.AlertIngestionService;
import io.github.chdurai.agenticbanking.incident.AlertSignal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Telemetry-driven incident detection: watches HTTP server metrics and opens an incident
 * with the agent pipeline when an endpoint's 5xx ratio breaches the configured threshold.
 * The agentic flow then triages, diagnoses (runbooks + knowledge graph) and plans
 * remediation exactly as for human-reported incidents.
 *
 * Stateless-ish and safe: the first sample per endpoint only establishes a baseline;
 * a per-endpoint cooldown prevents alert storms. In production the same watcher would
 * consume OpenTelemetry/Prometheus instead of the local registry.
 */
@Component
public class AnomalyWatcher {

    private static final Logger log = LoggerFactory.getLogger(AnomalyWatcher.class);

    private final MeterRegistry registry;
    private final AlertIngestionService alerts;
    private final AgentMetrics metrics;
    private final io.github.chdurai.agenticbanking.config.AgentProperties properties;

    private final Map<String, long[]> baseline = new ConcurrentHashMap<>(); // uri -> [total, errors]
    private final Map<String, Instant> lastAlert = new ConcurrentHashMap<>();

    public AnomalyWatcher(MeterRegistry registry, AlertIngestionService alerts,
                          AgentMetrics metrics, io.github.chdurai.agenticbanking.config.AgentProperties properties) {
        this.registry = registry;
        this.alerts = alerts;
        this.metrics = metrics;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${agents.anomaly.interval-ms:30000}",
            initialDelayString = "${agents.anomaly.interval-ms:30000}")
    public void check() {
        var config = properties.getAnomaly();
        if (!config.isEnabled()) {
            return;
        }
        Map<String, long[]> current = snapshot();
        current.forEach((uri, counts) -> {
            long[] base = baseline.put(uri, counts);
            if (base == null) {
                return; // first sighting establishes the baseline
            }
            long deltaTotal = counts[0] - base[0];
            long deltaErrors = counts[1] - base[1];
            if (deltaTotal < config.getMinSamples() || deltaErrors <= 0) {
                return;
            }
            double rate = (double) deltaErrors / deltaTotal;
            if (rate < config.getErrorRateThreshold() || inCooldown(uri, config.getCooldownMinutes())) {
                return;
            }
            lastAlert.put(uri, Instant.now());
            metrics.recordCounter("anomaly.detected", "uri", uri);
            openIncident(uri, deltaTotal, deltaErrors, rate);
        });
        baseline.keySet().retainAll(current.keySet());
    }

    private Map<String, long[]> snapshot() {
        Map<String, long[]> byUri = new HashMap<>();
        for (Timer timer : registry.find("http.server.requests").timers()) {
            String uri = timer.getId().getTag("uri");
            if (uri == null || "root".equals(uri)) {
                continue;
            }
            long[] counts = byUri.computeIfAbsent(uri, k -> new long[2]);
            long n = timer.count();
            counts[0] += n;
            String status = timer.getId().getTag("status");
            if (status != null && status.startsWith("5")) {
                counts[1] += n;
            }
        }
        return byUri;
    }

    private boolean inCooldown(String uri, int cooldownMinutes) {
        Instant last = lastAlert.get(uri);
        return last != null && last.plusSeconds(cooldownMinutes * 60L).isAfter(Instant.now());
    }

    private void openIncident(String uri, long total, long errors, double rate) {
        String service = uri.replaceAll("^/api/([^/]+).*$", "$1-service").replace("/", "-");
        AlertSignal signal = new AlertSignal(
                "Elevated HTTP 5xx on " + uri,
                "Anomaly watcher detected %d of %d recent requests failing (%.0f%% 5xx) on %s. "
                        .formatted(errors, total, rate * 100, uri)
                        + "Investigate recent deployments and downstream dependencies for this service.",
                service,
                "production",
                "anomaly-watcher",
                "http_5xx_ratio",
                List.of("auto-detected", "availability"),
                Instant.now());
        var receipt = alerts.ingest(signal);
        log.warn("[anomaly] {} -> queued alert {}", uri, receipt.alertId());
    }
}
