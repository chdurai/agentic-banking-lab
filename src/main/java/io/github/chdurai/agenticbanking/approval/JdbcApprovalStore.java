package io.github.chdurai.agenticbanking.approval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.List;

/** Approvals survive restarts: queryable columns plus the full request as JSON, versioned for CAS updates. */
public class JdbcApprovalStore implements ApprovalStore {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    public JdbcApprovalStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(ApprovalRequest r) {
        var tx = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                        java.util.Objects.requireNonNull(jdbc.getDataSource())));
        tx.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO approvals (id, flow, subject_id, status, version, expires_at, created_at, body, tenant_id, case_id) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?)",
                r.id(), r.flow(), r.subjectId(), r.status().name(), r.version(),
                Timestamp.from(r.expiresAt()), Timestamp.from(r.createdAt()), json(r), tenant(), io.github.chdurai.agenticbanking.cases.CaseExecution.current());
            var seal = ApprovalEvidenceSeal.of(r);
            jdbc.update("INSERT INTO approval_evidence_seal (tenant_id, approval_id, schema_version, snapshot_json, sha256) "
                    + "VALUES (?,?,1,?,?)", tenant(), r.id(), seal.snapshotJson(), seal.sha256());
        });
    }

    @Override
    public ApprovalRequest find(String id) {
        List<String> rows = jdbc.queryForList("SELECT body FROM approvals WHERE id = ? AND tenant_id=?", String.class, id, tenant());
        return rows.isEmpty() ? null : read(rows.get(0));
    }

    @Override
    public boolean replace(ApprovalRequest expected, ApprovalRequest u) {
        if (!expected.id().equals(u.id()) || !ApprovalEvidenceSeal.of(expected).equals(ApprovalEvidenceSeal.of(u))) {
            throw new IllegalStateException("Frozen approval evidence, action and authority cannot change");
        }
        verifySeal(u, false);
        return jdbc.update("UPDATE approvals SET status=?, version=?, body=? WHERE id=? AND version=? AND tenant_id=?",
                u.status().name(), u.version(), json(u), u.id(), expected.version(), tenant()) == 1;
    }

    @Override
    public List<ApprovalRequest> list(ApprovalRequest.Status status, int limit) {
        String sql = "SELECT body FROM approvals WHERE tenant_id=?" + (status == null ? "" : " AND status = ?")
                + " ORDER BY created_at DESC LIMIT " + Math.max(1, limit);
        List<String> rows = status == null ? jdbc.queryForList(sql, String.class, tenant())
                : jdbc.queryForList(sql, String.class, tenant(), status.name());
        return rows.stream().map(this::read).toList();
    }

    private static String tenant() { return io.github.chdurai.agenticbanking.security.CallerContext.current().tenantId(); }

    @Override public List<ApprovalRequest> forCase(String caseId) {
        return jdbc.queryForList("SELECT body FROM approvals WHERE tenant_id=? AND case_id=?", String.class,
                tenant(), caseId).stream().map(this::read).toList();
    }

    private String json(ApprovalRequest r) {
        try {
            return mapper.writeValueAsString(r);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private ApprovalRequest read(String body) {
        try {
            ApprovalRequest request = mapper.readValue(body, ApprovalRequest.class);
            verifySeal(request, false);
            return request;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public ApprovalEvidenceSeal requireSeal(ApprovalRequest request) {
        return verifySeal(request, true);
    }

    private ApprovalEvidenceSeal verifySeal(ApprovalRequest request, boolean required) {
        var rows = jdbc.query("SELECT schema_version, snapshot_json, sha256 FROM approval_evidence_seal WHERE tenant_id=? AND approval_id=?",
                (rs, n) -> {
                    if (rs.getInt("schema_version") != 1) {
                        throw new IllegalStateException("Unsupported frozen evidence schema; execution is blocked");
                    }
                    return new ApprovalEvidenceSeal(rs.getString("snapshot_json"), rs.getString("sha256"));
                },
                tenant(), request.id());
        if (rows.isEmpty()) {
            if (required) throw new IllegalStateException("Frozen evidence is missing; execution is blocked");
            return null; // Historical approvals remain readable; they are not certified recovery checkpoints.
        }
        var actual = ApprovalEvidenceSeal.of(request);
        if (!actual.equals(rows.getFirst())) {
            throw new IllegalStateException("Frozen approval evidence integrity check failed");
        }
        return actual;
    }
}
