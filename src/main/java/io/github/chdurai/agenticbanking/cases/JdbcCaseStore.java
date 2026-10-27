package io.github.chdurai.agenticbanking.cases;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** JDBC system of record (H2 locally, Postgres/Azure SQL in production). Schema: db/migration. */
public class JdbcCaseStore implements CaseStore {

    private static final RowMapper<CaseRecord> CASE = (rs, n) -> new CaseRecord(
            rs.getString("case_id"), rs.getString("flow"), rs.getString("subject_ref"),
            rs.getString("idempotency_key"), rs.getString("request_hash"),
            CaseStatus.valueOf(rs.getString("status")), rs.getLong("version"), rs.getString("result_json"),
            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
            rs.getString("tenant_id"), rs.getString("request_json"));
    private static final RowMapper<CaseLink> LINK = (rs, n) -> new CaseLink(rs.getString("id"), rs.getString("tenant_id"),
            rs.getString("from_case"), rs.getString("to_case"), CaseLink.Type.valueOf(rs.getString("type")),
            rs.getString("created_by"), rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public JdbcCaseStore(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    @Override
    public Claim claim(String flow, String idempotencyKey, String requestHash, String subjectRef) {
        return enqueue(flow, idempotencyKey, requestHash, subjectRef, null);
    }

    @Override
    public Claim enqueue(String flow, String idempotencyKey, String requestHash, String subjectRef, String requestJson) {
        Timestamp now = Timestamp.from(Instant.now());
        String id = UUID.randomUUID().toString();
        try {
            tx.executeWithoutResult(s -> {
                jdbc.update("INSERT INTO cases (case_id, flow, subject_ref, idempotency_key, request_hash, status, "
                        + "version, created_at, updated_at, tenant_id, request_json) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                        id, flow, subjectRef, idempotencyKey, requestHash, CaseStatus.OPEN.name(), 0, now, now,
                        tenant(), requestJson);
                insertEvent(id, "case.opened", "system", null, now);
            });
            return new Claim(find(id).orElseThrow(), true);
        } catch (DuplicateKeyException dup) {
            CaseRecord existing = jdbc.queryForObject(
                    "SELECT * FROM cases WHERE tenant_id = ? AND flow = ? AND idempotency_key = ?", CASE,
                    tenant(), flow, idempotencyKey);
            if (!existing.requestHash().equals(requestHash)) {
                throw new CaseStoreExceptions.IdempotencyConflictException(
                        "Idempotency-Key reused with a different request body");
            }
            return new Claim(existing, false);
        }
    }

    @Override
    public CaseRecord transition(String caseId, long expectedVersion, CaseStatus status, String resultJson,
                                 String actor, String eventType, OutboxMessage out) {
        tx.executeWithoutResult(s -> {
            Timestamp now = Timestamp.from(Instant.now());
            int updated = jdbc.update("UPDATE cases SET status = ?, version = version + 1, "
                    + "result_json = COALESCE(?, result_json), updated_at = ? WHERE tenant_id = ? AND case_id = ? AND version = ?",
                    status.name(), resultJson, now, tenant(), caseId, expectedVersion);
            if (updated == 0) {
                if (find(caseId).isEmpty()) {
                    throw new IllegalArgumentException("Unknown case " + caseId);
                }
                throw new CaseStoreExceptions.StaleVersionException(
                        "Case %s changed since version %d".formatted(caseId, expectedVersion));
            }
            insertEvent(caseId, eventType, actor, resultJson, now);
            if (out != null) {
                jdbc.update("INSERT INTO outbox (case_id, type, flow, subject_ref, payload_json, attempts, "
                        + "published, created_at) VALUES (?,?,?,?,?,0,FALSE,?)",
                        caseId, out.type(), out.flow(), out.subjectRef(), out.payloadJson(), now);
            }
        });
        return find(caseId).orElseThrow();
    }

    private void insertEvent(String caseId, String type, String actor, String payload, Timestamp at) {
        Long next = jdbc.queryForObject("SELECT COALESCE(MAX(seq), 0) + 1 FROM case_events WHERE case_id = ?",
                Long.class, caseId);
        jdbc.update("INSERT INTO case_events (case_id, seq, type, actor, payload_json, at) VALUES (?,?,?,?,?,?)",
                caseId, next, type, actor, payload, at);
    }

    @Override
    public Optional<CaseRecord> find(String caseId) {
        return jdbc.query("SELECT * FROM cases WHERE tenant_id = ? AND case_id = ?", CASE, tenant(), caseId).stream().findFirst();
    }

    @Override
    public List<CaseEvent> events(String caseId) {
        if (find(caseId).isEmpty()) return List.of();
        return jdbc.query("SELECT * FROM case_events WHERE case_id = ? ORDER BY seq",
                (rs, n) -> new CaseEvent(rs.getString("case_id"), rs.getLong("seq"), rs.getString("type"),
                        rs.getString("actor"), rs.getString("payload_json"), rs.getTimestamp("at").toInstant()),
                caseId);
    }

    @Override
    public List<OutboxMessage> pendingOutbox(int limit) {
        return jdbc.query("SELECT o.* FROM outbox o JOIN cases c ON c.case_id=o.case_id "
                        + "WHERE c.tenant_id = ? AND o.published = FALSE ORDER BY o.id LIMIT ?",
                (rs, n) -> new OutboxMessage(rs.getLong("id"), rs.getString("case_id"), rs.getString("type"),
                        rs.getString("flow"), rs.getString("subject_ref"), rs.getString("payload_json"),
                        rs.getInt("attempts"), rs.getTimestamp("created_at").toInstant()),
                tenant(), limit);
    }

    @Override
    public void markPublished(long outboxId) {
        jdbc.update("UPDATE outbox SET published = TRUE WHERE id = ? AND case_id IN "
                + "(SELECT case_id FROM cases WHERE tenant_id=?)", outboxId, tenant());
    }

    @Override
    public void markFailed(long outboxId) {
        jdbc.update("UPDATE outbox SET attempts = attempts + 1 WHERE id = ? AND case_id IN "
                + "(SELECT case_id FROM cases WHERE tenant_id=?)", outboxId, tenant());
    }

    @Override
    public long countCases() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM cases WHERE tenant_id=?", Long.class, tenant());
        return n == null ? 0 : n;
    }

    @Override public List<CaseRecord> queued(int limit) {
        return jdbc.query("SELECT * FROM cases WHERE tenant_id=? AND status='OPEN' AND request_json IS NOT NULL ORDER BY created_at LIMIT ?",
                CASE, tenant(), limit);
    }
    @Override public long countQueued() {
        return java.util.Objects.requireNonNull(jdbc.queryForObject(
                "SELECT COUNT(*) FROM cases WHERE tenant_id=? AND status='OPEN' AND request_json IS NOT NULL",
                Long.class, tenant()));
    }
    @Override public List<CaseRecord> running(int limit) {
        return jdbc.query("SELECT * FROM cases WHERE tenant_id=? AND status='RUNNING' ORDER BY updated_at LIMIT ?",
                CASE, tenant(), limit);
    }
    @Override public List<CaseRecord> page(String afterId, int limit) {
        return jdbc.query("SELECT * FROM cases WHERE tenant_id=? AND case_id > ? ORDER BY case_id LIMIT ?",
                CASE, tenant(), afterId == null ? "" : afterId, limit);
    }
    private static String tenant() { return io.github.chdurai.agenticbanking.security.CallerContext.current().tenantId(); }

    @Override public CaseLink.Creation link(String fromCase, String toCase, CaseLink.Type type) {
        if (type == null) throw new IllegalArgumentException("Link type is required");
        String id = UUID.randomUUID().toString();
        var now = Timestamp.from(Instant.now());
        try {
            tx.executeWithoutResult(status -> {
                int inserted = jdbc.update("""
                        INSERT INTO case_link (id, tenant_id, from_case, to_case, type, created_by, created_at)
                        SELECT ?, ?, s.case_id, t.case_id, ?, ?, ? FROM cases s JOIN cases t ON t.case_id=?
                        WHERE s.case_id=? AND s.tenant_id=? AND t.tenant_id=? AND s.flow=? AND t.flow=?
                        """, id, tenant(), type.name(), io.github.chdurai.agenticbanking.security.CallerContext.current().actor(), now,
                        toCase, fromCase, tenant(), tenant(), type.sourceFlow(), type.targetFlow());
                if (inserted != 1) throw new IllegalArgumentException("Link endpoints unavailable or flow types do not match");
                jdbc.update("""
                        INSERT INTO outbox (case_id, type, flow, subject_ref, payload_json, attempts, published, created_at)
                        SELECT case_id, 'case.linked', flow, subject_ref, ?, 0, FALSE, ? FROM cases
                        WHERE case_id=? AND tenant_id=?
                        """, "{\"linkId\":\"" + id + "\"}", now, fromCase, tenant());
            });
            return new CaseLink.Creation(findLink(id).orElseThrow(), true);
        } catch (DuplicateKeyException e) {
            var existing = jdbc.query("SELECT * FROM case_link WHERE tenant_id=? AND from_case=? AND to_case=? AND type=?",
                    LINK, tenant(), fromCase, toCase, type.name()).stream().findFirst();
            if (existing.isEmpty()) throw e;
            return new CaseLink.Creation(existing.get(), false);
        }
    }

    @Override public Optional<CaseLink> findLink(String id) {
        return jdbc.query("""
                SELECT l.* FROM case_link l JOIN cases s ON s.case_id=l.from_case JOIN cases t ON t.case_id=l.to_case
                WHERE l.id=? AND l.tenant_id=? AND s.tenant_id=? AND t.tenant_id=?
                """, LINK, id, tenant(), tenant(), tenant()).stream().findFirst();
    }

    @Override public List<CaseLink> links(String caseId, String afterId, int limit) {
        return jdbc.query("""
                SELECT l.* FROM case_link l JOIN cases s ON s.case_id=l.from_case JOIN cases t ON t.case_id=l.to_case
                WHERE l.tenant_id=? AND s.tenant_id=? AND t.tenant_id=? AND (l.from_case=? OR l.to_case=?)
                AND l.id>? ORDER BY l.id LIMIT ?
                """, LINK, tenant(), tenant(), tenant(), caseId, caseId, afterId == null ? "" : afterId, limit);
    }
}
