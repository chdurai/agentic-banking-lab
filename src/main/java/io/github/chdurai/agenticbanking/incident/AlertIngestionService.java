package io.github.chdurai.agenticbanking.incident;

import io.github.chdurai.agenticbanking.ops.AgentMetrics;
import io.github.chdurai.agenticbanking.ops.PlatformEvent;
import io.github.chdurai.agenticbanking.ops.PlatformEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Alert webhook ingestion with queue/worker and sliding-window dedupe.
 */
@Component
public class AlertIngestionService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AlertIngestionService.class);

    public record Receipt(String alertId, boolean accepted, boolean ignored, String reason) {}

    private record QueuedAlert(String alertId, AlertSignal signal, Instant enqueuedAt,
                               io.github.chdurai.agenticbanking.security.CallerContext caller) {}

    private final AlertIngestionProperties props;
    private final IncidentOrchestrator incidents;
    private final PlatformEventPublisher events;
    private final AgentMetrics metrics;
    private final ArrayDeque<QueuedAlert> queue = new ArrayDeque<>();
    private final Map<String, Instant> recentFingerprints = new ConcurrentHashMap<>();
    private JdbcAlertQueue durable;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private io.github.chdurai.agenticbanking.security.CallerProperties callers = new io.github.chdurai.agenticbanking.security.CallerProperties(false, Map.of(), java.util.Set.of());

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setDurableQueue(JdbcAlertQueue queue) { this.durable = queue; }

    public AlertIngestionService(AlertIngestionProperties props, IncidentOrchestrator incidents,
                                 PlatformEventPublisher events, AgentMetrics metrics) {
        this.props = props;
        this.incidents = incidents;
        this.events = events;
        this.metrics = metrics;
    }

    public synchronized Receipt ingest(AlertSignal signal) {
        if (!props.enabled()) {
            return new Receipt(null, false, true, "ingestion disabled");
        }
        if (selfWatch(signal)) {
            metrics.recordCounter("incident.alert.ignored", "reason", "self_watch");
            return new Receipt(null, false, true, "self-watch signal ignored");
        }
        if (durable != null) {
            var receipt = durable.enqueue(signal, props.maxQueueSize());
            metrics.recordCounter("incident.alert.accepted", "source", safeTag(signal.source()));
            return new Receipt(receipt.alertId(), true, false, null);
        }
        String alertId = UUID.randomUUID().toString();
        if (queue.size() >= props.maxQueueSize()) {
            metrics.recordCounter("incident.alert.rejected", "reason", "queue_full");
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
                    "Alert queue is full; retry later. No accepted alert was evicted.");
        }
        queue.addLast(new QueuedAlert(alertId, signal, Instant.now(), io.github.chdurai.agenticbanking.security.CallerContext.current()));
        metrics.recordCounter("incident.alert.accepted", "source", safeTag(signal.source()));
        return new Receipt(alertId, true, false, null);
    }

    @Scheduled(fixedDelayString = "${platform.incident.ingestion.worker-interval-ms:5000}")
    public void drain() {
        if (!props.enabled()) {
            return;
        }
        if (durable != null) {
            drainDurable();
            return;
        }
        int max = Math.max(1, props.maxBatchPerRun());
        for (int i = 0; i < max; i++) {
            QueuedAlert next;
            synchronized (this) {
                next = queue.pollFirst();
            }
            if (next == null) {
                break;
            }
            try (var ignored = next.caller().open()) { process(next); }
        }
        pruneFingerprints();
    }

    private void drainDurable() {
        for (var caller : io.github.chdurai.agenticbanking.security.TenantMaintenance.scopes(callers)) {
            try (var ignored = caller.open()) {
                for (int i = 0; i < props.maxBatchPerRun(); i++) {
                    var next = durable.claim(props.correlationWindowMinutes());
                    if (next.isEmpty()) break;
                    var claim = next.get();
                    if (claim.correlated()) {
                        metrics.recordCounter("incident.alert.correlated", "service", safeTag(claim.signal().service()));
                        events.publish(PlatformEvent.of("incident.correlated", "incident", claim.alertId(),
                                "correlated with a durable completed alert in the configured window"));
                        continue;
                    }
                    try {
                        handle(claim.alertId(), claim.signal());
                        durable.completed(claim);
                    } catch (RuntimeException failure) {
                        try { durable.uncertain(claim); }
                        catch (RuntimeException persistenceFailure) { failure.addSuppressed(persistenceFailure); }
                        metrics.recordCounter("incident.alert.uncertain", "reason", "processing_failed");
                        log.error("Accepted alert {} requires reconciliation; no automatic resend", claim.alertId(), failure);
                    }
                }
            }
        }
    }

    public java.util.Optional<JdbcAlertQueue.Receipt> receipt(String id) {
        if (durable == null) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "Durable alert receipts require JDBC persistence");
        return durable.find(id);
    }

    private void process(QueuedAlert item) {
        AlertSignal s = item.signal();
        String fingerprint = io.github.chdurai.agenticbanking.security.CallerContext.key(fingerprintOf(s));
        Instant now = Instant.now();
        Instant seen = recentFingerprints.get(fingerprint);
        if (seen != null && Duration.between(seen, now).toMinutes() < props.correlationWindowMinutes()) {
            metrics.recordCounter("incident.alert.correlated", "service", safeTag(s.service()));
            events.publish(PlatformEvent.of("incident.correlated", "incident", item.alertId(),
                    "deduped by fingerprint in sliding window"));
            return;
        }
        handle(item.alertId(), s);
        recentFingerprints.put(fingerprint, now);
    }

    private void handle(String alertId, AlertSignal s) {
        Incident incident = new Incident(
                "ALERT-" + alertId,
                s.title(),
                s.description(),
                s.service(),
                s.environment() == null ? "production" : s.environment(),
                "alert-webhook",
                s.tags() == null ? List.of("webhook-alert") : s.tags());
        incidents.handle(incident);
    }

    private void pruneFingerprints() {
        Instant now = Instant.now();
        long windowMs = Math.max(1, props.correlationWindowMinutes()) * 60_000L;
        recentFingerprints.entrySet().removeIf(e -> Duration.between(e.getValue(), now).toMillis() > windowMs);
    }

    private static boolean selfWatch(AlertSignal signal) {
        String source = safeTag(signal.source());
        String service = safeTag(signal.service());
        if (source.contains("agentic-banking") || service.contains("agentic-banking")) {
            return true;
        }
        return signal.tags() != null && signal.tags().stream()
                .anyMatch(t -> safeTag(t).contains("self-watch"));
    }

    private static String fingerprintOf(AlertSignal signal) {
        return JdbcAlertQueue.fingerprint(signal);
    }

    private static String safeTag(String text) {
        return text == null ? "unknown" : text.toLowerCase(Locale.ROOT);
    }
}
