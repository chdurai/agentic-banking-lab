package io.github.chdurai.agenticbanking.cases;

/** Correlates approvals created by an orchestrator to its durable case, without changing business IDs. */
public final class CaseExecution {
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();
    private CaseExecution() {}
    public static String current() { return CURRENT.get(); }
    public static io.github.chdurai.agenticbanking.security.CallerContext.Scope open(String caseId) {
        String previous = CURRENT.get();
        CURRENT.set(caseId);
        return () -> { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); };
    }
}
