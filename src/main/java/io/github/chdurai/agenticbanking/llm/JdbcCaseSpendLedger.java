package io.github.chdurai.agenticbanking.llm;

import io.github.chdurai.agenticbanking.security.CallerContext;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.UUID;

/** Reservations are committed debits before dispatch; process loss cannot refund ambiguous provider work. */
public final class JdbcCaseSpendLedger implements CaseSpendLedger {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate independent;
    private final TransactionTemplate atomic;

    public JdbcCaseSpendLedger(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        var manager = new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource()));
        independent = new TransactionTemplate(manager);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        atomic = new TransactionTemplate(manager);
        atomic.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override public Account open(String caseId, Limits requested) {
        if (caseId == null || caseId.isBlank() || caseId.length() > 64 || requested.tokens() < 0 || requested.costMicros() < -1) {
            throw new IllegalArgumentException("Invalid durable case spending account");
        }
        String tenant = CallerContext.current().tenantId();
        // Isolate a PostgreSQL uniqueness race from the transaction that subsequently locks the account.
        independent.executeWithoutResult(status -> {
            try {
                jdbc.update("""
                        INSERT INTO case_spend_account
                        (tenant_id,case_id,token_limit,cost_limit_micros,tokens_debited,cost_debited_micros)
                        VALUES (?,?,?,?,0,0)
                        """, tenant, caseId, requested.tokens(), requested.costMicros());
            } catch (DuplicateKeyException existing) {
                // Retries and peer replicas reuse the same account; limits can tighten but never reset or loosen.
            }
        });
        Limits effective = Objects.requireNonNull(atomic.execute(status -> {
            var row = lock(tenant, caseId);
            long tokens = tighterTokens(row.limits.tokens(), requested.tokens());
            long cost = tighterCost(row.limits.costMicros(), requested.costMicros());
            jdbc.update("UPDATE case_spend_account SET token_limit=?,cost_limit_micros=? WHERE tenant_id=? AND case_id=?",
                    tokens, cost, tenant, caseId);
            return new Limits(tokens, cost);
        }));
        return new Account() {
            @Override public Limits limits() { return effective; }
            @Override public Attempt reserve(long tokens, long micros, boolean costPriced) {
                if (tokens < 1 || micros < 0) throw new IllegalArgumentException("Invalid durable spend estimate");
                String id = UUID.randomUUID().toString();
                atomic.executeWithoutResult(status -> {
                    var row = lock(tenant, caseId);
                    if (row.limits.costMicros() >= 0 && !costPriced) {
                        throw new LlmException(LlmException.Kind.BUDGET, "Model pricing is required by the durable case cost limit");
                    }
                    if (tokens > Long.MAX_VALUE - row.tokens || micros > Long.MAX_VALUE - row.micros
                            || row.limits.tokens() > 0 && tokens > row.limits.tokens() - row.tokens
                            || row.limits.costMicros() >= 0 && micros > row.limits.costMicros() - row.micros) {
                        throw new LlmException(LlmException.Kind.BUDGET, "Durable lifetime case budget cannot admit provider call");
                    }
                    jdbc.update("""
                            UPDATE case_spend_account SET tokens_debited=?,cost_debited_micros=?
                            WHERE tenant_id=? AND case_id=?
                            """, row.tokens + tokens, row.micros + micros, tenant, caseId);
                    jdbc.update("""
                            INSERT INTO case_spend_attempt
                            (attempt_id,tenant_id,case_id,tokens_debited,cost_debited_micros,settled)
                            VALUES (?,?,?,?,?,FALSE)
                            """, id, tenant, caseId, tokens, micros);
                });
                return (actualTokens, actualMicros) -> settle(tenant, caseId, id, actualTokens, actualMicros);
            }
        };
    }

    private void settle(String tenant, String caseId, String id, long tokens, long micros) {
        if (tokens < 0 || micros < 0) throw new IllegalArgumentException("Invalid durable spend settlement");
        atomic.executeWithoutResult(status -> {
            var account = lock(tenant, caseId);
            var rows = jdbc.query("""
                    SELECT tokens_debited,cost_debited_micros,settled FROM case_spend_attempt
                    WHERE tenant_id=? AND case_id=? AND attempt_id=?
                    """, (r, n) -> new Debit(r.getLong(1), r.getLong(2), r.getBoolean(3)), tenant, caseId, id);
            if (rows.size() != 1) throw new DataRetrievalFailureException("Durable spend attempt unavailable");
            Debit old = rows.getFirst();
            if (old.settled) {
                if (old.tokens != tokens || old.micros != micros) {
                    throw new IllegalStateException("Conflicting durable spend settlement");
                }
                return;
            }
            long nextTokens = Math.addExact(account.tokens - old.tokens, tokens);
            long nextMicros = Math.addExact(account.micros - old.micros, micros);
            jdbc.update("""
                    UPDATE case_spend_account SET tokens_debited=?,cost_debited_micros=? WHERE tenant_id=? AND case_id=?
                    """, nextTokens, nextMicros, tenant, caseId);
            jdbc.update("""
                    UPDATE case_spend_attempt SET tokens_debited=?,cost_debited_micros=?,settled=TRUE WHERE attempt_id=?
                    """, tokens, micros, id);
        });
    }

    private record Balance(Limits limits, long tokens, long micros) {}
    private record Debit(long tokens, long micros, boolean settled) {}

    private Balance lock(String tenant, String caseId) {
        var rows = jdbc.query("""
                SELECT token_limit,cost_limit_micros,tokens_debited,cost_debited_micros
                FROM case_spend_account WHERE tenant_id=? AND case_id=? FOR UPDATE
                """, (r, n) -> new Balance(new Limits(r.getLong(1), r.getLong(2)), r.getLong(3), r.getLong(4)), tenant, caseId);
        if (rows.size() != 1) throw new DataRetrievalFailureException("Durable case spend account unavailable");
        return rows.getFirst();
    }

    private static long tighterTokens(long a, long b) { return a == 0 ? b : b == 0 ? a : Math.min(a, b); }
    private static long tighterCost(long a, long b) { return a == -1 ? b : b == -1 ? a : Math.min(a, b); }
}
