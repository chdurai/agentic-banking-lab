package io.github.chdurai.agenticbanking.approval;

import java.util.List;

/** Executes an approved {@link ActionSpec} outside any lock; the returned lines become the execution log. */
public interface ApprovalActionHandler {
    String type();

    List<String> execute(ApprovalRequest request);
}
