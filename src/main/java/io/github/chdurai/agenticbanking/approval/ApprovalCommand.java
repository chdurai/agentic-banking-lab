package io.github.chdurai.agenticbanking.approval;

/** A human decision expressed as data. */
public sealed interface ApprovalCommand {
    String note();
    String reasonCode();

    record Approve(String reasonCode, String note) implements ApprovalCommand {}

    record Reject(String reasonCode, String note) implements ApprovalCommand {}

    /** Replace the AI/system proposal with the reviewer's own decision; a reason is mandatory. */
    record OverrideDecision(String reasonCode, String reason) implements ApprovalCommand {
        @Override
        public String note() {
            return reason;
        }
    }
}
