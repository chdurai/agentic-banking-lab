package io.github.chdurai.agenticbanking.flow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chdurai.agenticbanking.cases.CaseService;
import io.github.chdurai.agenticbanking.flow.plan.DagPlan;
import io.github.chdurai.agenticbanking.security.CallerContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class JdbcFlowExecutionJournal implements FlowExecutionJournal {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;

    public JdbcFlowExecutionJournal(JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper json) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.json = json;
    }

    @Override public Owner begin(DagPlan plan, String caseId) {
        if (plan.planVersion() != 1) throw new IllegalStateException("Unsupported execution plan version; scheduling is blocked");
        String tenant = CallerContext.current().tenantId();
        String id = UUID.randomUUID().toString();
        String key = caseId == null ? id : caseId;
        if (caseId != null && jdbc.queryForObject("SELECT COUNT(*) FROM cases WHERE tenant_id=? AND case_id=? AND flow=?",
                Integer.class, tenant, caseId, plan.flow()) != 1) {
            throw new IllegalStateException("Durable execution case is unavailable or flow does not match");
        }
        try {
            jdbc.update("INSERT INTO flow_execution_slot (tenant_id, execution_key, flow) VALUES (?,?,?)", tenant, key, plan.flow());
        } catch (DuplicateKeyException existing) {
            // The committed slot serializes execution owners; it is not a claim to replay earlier work.
        }
        Owner owner = new Owner(id, tenant, UUID.randomUUID().toString());
        String planJson = encode(plan);
        tx.executeWithoutResult(status -> {
            var active = jdbc.query("SELECT run_id FROM flow_execution_slot WHERE tenant_id=? AND execution_key=? AND flow=? FOR UPDATE",
                    (rs, n) -> rs.getString("run_id"), tenant, key, plan.flow());
            if (active.size() != 1) throw new IllegalStateException("Execution ownership slot is missing");
            if (active.getFirst() != null) {
                String previous = jdbc.queryForObject("SELECT status FROM flow_execution_run WHERE run_id=? AND tenant_id=?",
                        String.class, active.getFirst(), tenant);
                if ("RUNNING".equals(previous) || "UNCERTAIN".equals(previous) || "CANCELLED".equals(previous)) {
                    throw new IllegalStateException("Prior execution requires reconciliation; automatic replay is blocked");
                }
            }
            jdbc.update("INSERT INTO flow_execution_run (run_id, tenant_id, case_id, execution_key, flow, owner_token, status, "
                            + "schema_version, plan_json, plan_sha256, started_at) VALUES (?,?,?,?,?,?,'RUNNING',1,?,?,?)",
                    id, tenant, caseId, key, plan.flow(), owner.token(), planJson, CaseService.sha256(planJson), now());
            jdbc.update("UPDATE flow_execution_slot SET run_id=? WHERE tenant_id=? AND execution_key=? AND flow=?",
                    id, tenant, key, plan.flow());
        });
        return owner;
    }

    @Override public void started(Owner owner, String node, List<String> predecessors) {
        tx.executeWithoutResult(status -> {
            lock(owner);
            jdbc.update("INSERT INTO flow_node_attempt (run_id, node_id, status, predecessor_json, started_at) "
                    + "VALUES (?,?,'RUNNING',?,?)", owner.runId(), node, encode(predecessors), now());
        });
    }

    @Override public void completed(Owner owner, FlowRuntime.NodeResult<?> result) {
        String value = result.value() == null ? null : encode(result.value());
        String failure = result.status() == FlowRuntime.Status.TIMEOUT ? "DEADLINE_EXCEEDED"
                : result.error() == null ? null : result.error() instanceof InterruptedException
                ? "INTERRUPTED" : "EXECUTION_FAILED";
        tx.executeWithoutResult(status -> {
            lock(owner);
            int changed = jdbc.update("UPDATE flow_node_attempt SET status=?, result_json=?, result_sha256=?, "
                            + "failure_category=?, duration_ms=?, finished_at=? WHERE run_id=? AND node_id=? AND status='RUNNING'",
                    result.status().name(), value, value == null ? null : CaseService.sha256(value), failure,
                    result.durationMs(), now(), owner.runId(), result.agentId());
            if (changed != 1) throw new IllegalStateException("Node attempt is missing or already committed");
        });
    }

    @Override public void finished(Owner owner, String status) {
        if (!Set.of("COMPLETED", "STOPPED", "CANCELLED", "UNCERTAIN").contains(status)) {
            throw new IllegalArgumentException("Unknown execution outcome");
        }
        tx.executeWithoutResult(transaction -> {
            lock(owner);
            jdbc.update("UPDATE flow_node_attempt SET status='UNCERTAIN', failure_category='EXECUTION_INTERRUPTED', "
                    + "finished_at=? WHERE run_id=? AND status='RUNNING'", now(), owner.runId());
            jdbc.update("UPDATE flow_execution_run SET status=?, finished_at=? WHERE run_id=? AND tenant_id=? AND owner_token=?",
                    status, now(), owner.runId(), owner.tenantId(), owner.token());
        });
    }

    private void lock(Owner owner) {
        if (!CallerContext.current().tenantId().equals(owner.tenantId())) {
            throw new IllegalStateException("Execution owner tenant mismatch");
        }
        var rows = jdbc.queryForList("SELECT status FROM flow_execution_run WHERE run_id=? AND tenant_id=? AND owner_token=? FOR UPDATE",
                String.class, owner.runId(), owner.tenantId(), owner.token());
        if (rows.size() != 1 || !"RUNNING".equals(rows.getFirst())) {
            throw new IllegalStateException("Execution owner is stale, missing or closed");
        }
    }

    @Override public List<RunView> runs(String caseId, int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Execution limit must be 1-100");
        return jdbc.query("SELECT * FROM flow_execution_run WHERE tenant_id=? AND case_id=? ORDER BY started_at DESC, run_id LIMIT ?",
                (rs, n) -> {
                    if (rs.getInt("schema_version") != 1) throw new IllegalStateException("Unsupported execution snapshot schema");
                    verify(rs.getString("plan_json"), rs.getString("plan_sha256"));
                    return new RunView(rs.getString("run_id"), rs.getString("flow"), rs.getString("status"),
                        rs.getInt("schema_version"), rs.getString("plan_sha256"), rs.getTimestamp("started_at").toInstant(),
                        rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toInstant());
                },
                CallerContext.current().tenantId(), caseId, limit);
    }

    @Override public List<AttemptView> attempts(String runId) {
        return jdbc.query("SELECT a.* FROM flow_node_attempt a JOIN flow_execution_run r ON r.run_id=a.run_id "
                        + "WHERE r.tenant_id=? AND r.run_id=? ORDER BY a.started_at, a.node_id",
                (rs, n) -> {
                    verify(rs.getString("result_json"), rs.getString("result_sha256"));
                    return new AttemptView(rs.getString("node_id"), rs.getString("status"), rs.getString("result_sha256"),
                        rs.getString("failure_category"), rs.getObject("duration_ms", Long.class),
                        rs.getTimestamp("started_at").toInstant(),
                        rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toInstant());
                },
                CallerContext.current().tenantId(), runId);
    }

    private String encode(Object value) {
        try {
            String encoded = json.writeValueAsString(value);
            if (encoded.length() > 1_000_000) throw new IllegalStateException("Execution snapshot exceeds journal limit");
            return encoded;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot persist execution snapshot", e);
        }
    }

    private static Timestamp now() { return Timestamp.from(Instant.now()); }

    private static void verify(String snapshot, String hash) {
        if (snapshot == null ? hash != null : !CaseService.sha256(snapshot).equals(hash)) {
            throw new IllegalStateException("Execution snapshot integrity check failed");
        }
    }
}
