package io.github.chdurai.agenticbanking.approval;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryApprovalStore implements ApprovalStore {

    private final Map<String, ApprovalRequest> requests = new ConcurrentHashMap<>();
    private final Map<String, String> cases = new ConcurrentHashMap<>();

    @Override
    public void insert(ApprovalRequest request) {
        requests.put(io.github.chdurai.agenticbanking.security.CallerContext.key(request.id()), request);
        if (io.github.chdurai.agenticbanking.cases.CaseExecution.current() != null) {
            cases.put(io.github.chdurai.agenticbanking.security.CallerContext.key(request.id()), io.github.chdurai.agenticbanking.cases.CaseExecution.current());
        }
    }

    @Override
    public ApprovalRequest find(String id) {
        return requests.get(io.github.chdurai.agenticbanking.security.CallerContext.key(id));
    }

    @Override
    public boolean replace(ApprovalRequest expected, ApprovalRequest updated) {
        return requests.replace(io.github.chdurai.agenticbanking.security.CallerContext.key(expected.id()), expected, updated);
    }

    @Override
    public List<ApprovalRequest> list(ApprovalRequest.Status status, int limit) {
        return requests.entrySet().stream()
                .filter(e -> e.getKey().startsWith(io.github.chdurai.agenticbanking.security.CallerContext.key("")))
                .map(Map.Entry::getValue)
                .filter(r -> status == null || r.status() == status)
                .sorted(Comparator.comparing(ApprovalRequest::createdAt).reversed())
                .limit(limit).toList();
    }

    @Override public List<ApprovalRequest> forCase(String caseId) {
        return requests.entrySet().stream()
                .filter(e -> e.getKey().startsWith(io.github.chdurai.agenticbanking.security.CallerContext.key("")))
                .filter(e -> caseId.equals(cases.get(e.getKey()))).map(Map.Entry::getValue).toList();
    }
}
