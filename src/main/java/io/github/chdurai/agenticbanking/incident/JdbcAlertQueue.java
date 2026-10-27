package io.github.chdurai.agenticbanking.incident;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chdurai.agenticbanking.cases.CaseService;
import io.github.chdurai.agenticbanking.security.CallerContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** Accepted obligations are never deleted on dispatch. RUNNING/UNCERTAIN require investigation, not blind replay. */
public final class JdbcAlertQueue {
    public record Receipt(String alertId, String status, String failureCategory, Instant createdAt, Instant updatedAt) {}
    public record Claim(String alertId, String tenantId, String ownerToken, AlertSignal signal, boolean correlated) {}
    private record Pending(String id, String fingerprint, String body) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;

    public JdbcAlertQueue(JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper json) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.json = json;
    }

    public Receipt enqueue(AlertSignal signal, int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("Alert queue capacity must be positive");
        String body = encode(signal);
        if (body.length() > 256_000) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Alert exceeds durable queue limit");
        ensureTenant();
        String id = UUID.randomUUID().toString();
        return java.util.Objects.requireNonNull(tx.execute(status -> {
            lockTenant();
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM alert_receipt WHERE tenant_id=? "
                    + "AND status IN ('PENDING','RUNNING','UNCERTAIN')", Integer.class, tenant());
            if (count == null || count >= capacity) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                        "Alert queue is full; retry later. No accepted alert was evicted.");
            }
            Instant now = Instant.now();
            jdbc.update("INSERT INTO alert_receipt (alert_id, tenant_id, status, fingerprint, signal_json, created_at, updated_at) "
                            + "VALUES (?,?,'PENDING',?,?,?,?)",
                    id, tenant(), fingerprint(signal), body, Timestamp.from(now), Timestamp.from(now));
            return new Receipt(id, "PENDING", null, now, now);
        }));
    }

    public Optional<Claim> claim(int correlationMinutes) {
        if (correlationMinutes < 1) throw new IllegalArgumentException("Correlation window must be positive");
        ensureTenant();
        return java.util.Objects.requireNonNull(tx.execute(status -> {
            lockTenant();
            var pending = jdbc.query("""
                    SELECT p.alert_id, p.fingerprint, p.signal_json FROM alert_receipt p
                    WHERE p.tenant_id=? AND p.status='PENDING' AND NOT EXISTS (
                        SELECT 1 FROM alert_receipt a WHERE a.tenant_id=p.tenant_id AND a.fingerprint=p.fingerprint
                        AND a.status IN ('RUNNING','UNCERTAIN'))
                    ORDER BY p.created_at, p.alert_id LIMIT 1
                    """, (rs, n) -> new Pending(rs.getString("alert_id"), rs.getString("fingerprint"), rs.getString("signal_json")), tenant());
            for (var row : pending) {
                String fingerprint = row.fingerprint();
                Timestamp now = Timestamp.from(Instant.now());
                Integer completed = jdbc.queryForObject("SELECT COUNT(*) FROM alert_receipt WHERE tenant_id=? AND fingerprint=? "
                                + "AND status='COMPLETED' AND updated_at>=?", Integer.class, tenant(), fingerprint,
                        Timestamp.from(now.toInstant().minusSeconds(correlationMinutes * 60L)));
                boolean correlated = completed != null && completed > 0;
                String id = row.id();
                String token = UUID.randomUUID().toString();
                AlertSignal signal = read(row.body());
                jdbc.update("UPDATE alert_receipt SET status=?, owner_token=?, updated_at=? WHERE tenant_id=? AND alert_id=?",
                        correlated ? "CORRELATED" : "RUNNING", token, now, tenant(), id);
                return Optional.of(new Claim(id, tenant(), token, signal, correlated));
            }
            return Optional.empty();
        }));
    }

    public void completed(Claim claim) { finish(claim, "COMPLETED", null); }
    public void uncertain(Claim claim) { finish(claim, "UNCERTAIN", "PROCESSING_OUTCOME_UNCERTAIN"); }

    private void finish(Claim claim, String status, String failure) {
        if (!tenant().equals(claim.tenantId())) throw new IllegalStateException("Alert claim tenant mismatch");
        int changed = jdbc.update("UPDATE alert_receipt SET status=?, failure_category=?, updated_at=? "
                        + "WHERE tenant_id=? AND alert_id=? AND owner_token=? AND status='RUNNING'",
                status, failure, Timestamp.from(Instant.now()), tenant(), claim.alertId(), claim.ownerToken());
        if (changed != 1) throw new IllegalStateException("Alert owner is stale, missing or closed");
    }

    public Optional<Receipt> find(String id) {
        return jdbc.query("SELECT * FROM alert_receipt WHERE tenant_id=? AND alert_id=?",
                (rs, n) -> new Receipt(rs.getString("alert_id"), rs.getString("status"), rs.getString("failure_category"),
                        rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()),
                tenant(), id).stream().findFirst();
    }

    private void ensureTenant() {
        try { jdbc.update("INSERT INTO alert_admission (tenant_id) VALUES (?)", tenant()); }
        catch (DuplicateKeyException exists) { /* Tenant admission lock already committed. */ }
    }

    private void lockTenant() {
        if (jdbc.queryForList("SELECT tenant_id FROM alert_admission WHERE tenant_id=? FOR UPDATE",
                String.class, tenant()).size() != 1) throw new IllegalStateException("Alert admission lock missing");
    }

    static String fingerprint(AlertSignal signal) {
        return CaseService.sha256(List.of(normalize(signal.environment() == null ? "production" : signal.environment()),
                normalize(signal.service()), normalize(signal.symptomKey()), normalize(signal.title())).stream()
                .map(value -> value.length() + ":" + value).collect(java.util.stream.Collectors.joining()));
    }

    private static String normalize(String value) { return value == null ? "" : value.strip().toLowerCase(Locale.ROOT); }
    private static String tenant() { return CallerContext.current().tenantId(); }

    private String encode(AlertSignal signal) {
        try { return json.writeValueAsString(signal); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Cannot persist alert signal", e); }
    }

    private AlertSignal read(String body) {
        try { return json.readValue(body, AlertSignal.class); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Cannot read accepted alert signal", e); }
    }
}
