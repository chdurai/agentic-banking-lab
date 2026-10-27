package io.github.chdurai.agenticbanking.approval;

import java.util.List;

/** Persistence port for approvals. Updates are optimistic: {@code replace} succeeds only for the expected version. */
public interface ApprovalStore {
    void insert(ApprovalRequest request);

    ApprovalRequest find(String id);

    /** Compare-and-set on version. @return true when applied. */
    boolean replace(ApprovalRequest expected, ApprovalRequest updated);

    List<ApprovalRequest> list(ApprovalRequest.Status status, int limit);

    List<ApprovalRequest> forCase(String caseId);
}
