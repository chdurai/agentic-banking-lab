package io.github.chdurai.agenticbanking.cases;

/** Lifecycle of a durable case. EXECUTION_FAILED is a separate fact from the human decision (APPROVED). */
public enum CaseStatus {
    OPEN, RUNNING, COMPLETED, BLOCKED, PENDING_APPROVAL, APPROVED, REJECTED, EXECUTION_FAILED, EXPIRED, FAILED
}
