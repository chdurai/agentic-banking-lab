package io.github.chdurai.agenticbanking.llm;

/** Conservative lifetime admission for a durable case, independent of an execution's transaction. */
public interface CaseSpendLedger {
    /** A zero token limit is unbounded; a cost limit of -1 is unbounded (zero is a real sub-micro-dollar cap). */
    record Limits(long tokens, long costMicros) {}
    interface Account {
        Limits limits();
        default Attempt reserve(long estimatedTokens, long estimatedCostMicros) {
            return reserve(estimatedTokens, estimatedCostMicros, true);
        }
        Attempt reserve(long estimatedTokens, long estimatedCostMicros, boolean costPriced);
    }
    interface Attempt {
        void settle(long actualTokens, long actualCostMicros);
    }
    Account open(String caseId, Limits requested);
}
