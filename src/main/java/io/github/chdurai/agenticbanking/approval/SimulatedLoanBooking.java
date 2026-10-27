package io.github.chdurai.agenticbanking.approval;

import io.github.chdurai.agenticbanking.cases.CaseService;
import io.github.chdurai.agenticbanking.security.CallerContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The receipt IS the simulated effect. No external banking adapter or mutation credentials are used. */
public class SimulatedLoanBooking implements RecoverableApprovalActionHandler {
    public static final String TYPE = "loan.simulated-booking.v1";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final JdbcApprovalStore approvals;

    public SimulatedLoanBooking(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.approvals = new JdbcApprovalStore(jdbc);
    }

    public static ActionSpec action() {
        return new ActionSpec(TYPE, Map.of("mode", "synthetic-only"));
    }

    @Override public String type() { return TYPE; }

    @Override public List<String> execute(ApprovalRequest request) {
        return java.util.Objects.requireNonNull(tx.execute(status -> {
            String tenant = CallerContext.current().tenantId();
            var locked = jdbc.queryForList("SELECT id FROM approvals WHERE tenant_id=? AND id=? FOR UPDATE",
                    String.class, tenant, request.id());
            if (locked.isEmpty()) throw new IllegalStateException("Approval unavailable in this tenant");
            ApprovalRequest approved = approvals.find(request.id());
            if (approved.status() != ApprovalRequest.Status.APPROVED
                    || !"loan".equals(approved.flow()) || !action().equals(approved.action())
                    || !ApprovalEvidenceSeal.of(approved).equals(ApprovalEvidenceSeal.of(request))) {
                throw new IllegalStateException("Simulation requires the original approved loan action");
            }
            var seal = approvals.requireSeal(approved);
            var packet = approved.evidencePacket();
            if (packet == null || packet.schemaVersion() != 1 || !"loan".equals(packet.flow())
                    || !approved.subjectId().equals(packet.subjectId())
                    || packet.agentFindings().isEmpty() || packet.policyEnvelope().isEmpty()
                    || packet.artifactSet().isEmpty()
                    || packet.agentFindings().stream().anyMatch(f -> f.agentId() == null || f.agentId().isBlank()
                    || !List.of("PASS", "WARN").contains(f.status()) || f.summary() == null || f.summary().isBlank())) {
                throw new IllegalStateException("Incomplete or failed evidence blocks simulated booking");
            }
            String key = "loan-sim:v1:" + CaseService.sha256(tenant + ":" + approved.id() + ":" + seal.sha256());
            var receipts = jdbc.queryForList("SELECT receipt_id FROM simulated_loan_receipt "
                    + "WHERE tenant_id=? AND approval_id=? AND evidence_sha256=? AND effect_key=?",
                    String.class, tenant, approved.id(), seal.sha256(), key);
            String receipt;
            if (receipts.isEmpty()) {
                receipt = "sim-" + UUID.randomUUID();
                jdbc.update("INSERT INTO simulated_loan_receipt "
                                + "(tenant_id, approval_id, effect_key, evidence_sha256, receipt_id, recorded_at) VALUES (?,?,?,?,?,?)",
                        tenant, approved.id(), key, seal.sha256(), receipt, Timestamp.from(Instant.now()));
            } else {
                receipt = receipts.getFirst();
            }
            return List.of("SIMULATED_ONLY: no funds moved", "effectKey=" + key,
                    "evidenceSha256=" + seal.sha256(), "receipt=" + receipt);
        }));
    }
}
