package io.github.chdurai.agenticbanking.approval;

/** Reacts to a finished human decision (resume the case, emit the final event, send notices). */
public interface ApprovalOutcomeListener {
    void onOutcome(ApprovalRequest decided);
}
