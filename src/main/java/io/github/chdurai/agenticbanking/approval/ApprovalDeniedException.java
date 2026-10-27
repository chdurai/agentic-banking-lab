package io.github.chdurai.agenticbanking.approval;

/** Reviewer is not allowed to decide this request (missing role, or deciding their own request). */
public class ApprovalDeniedException extends RuntimeException {
    public ApprovalDeniedException(String message) {
        super(message);
    }
}
