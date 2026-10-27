package io.github.chdurai.agenticbanking.flow.state;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class InMemoryCaseState implements CaseState {

    private final String caseId;
    private final String flow;
    private final io.github.chdurai.agenticbanking.ops.AgentActivityHub activity;
    private final List<Finding> findings = new ArrayList<>();
    private final List<AgentMessage> messages = new ArrayList<>();

    public InMemoryCaseState(String caseId) {
        this(caseId, "loan", null);
    }

    /** @param activity live-observability sink; null disables emission (tests, legacy paths) */
    public InMemoryCaseState(String caseId, String flow, io.github.chdurai.agenticbanking.ops.AgentActivityHub activity) {
        this.caseId = caseId;
        this.flow = flow;
        this.activity = activity;
    }

    @Override
    public String caseId() {
        return caseId;
    }

    @Override
    public synchronized Finding append(Finding f) {
        if (f.provenance() == null) {
            throw new IllegalArgumentException("Finding without provenance rejected: " + f.agentId());
        }
        if (!caseId.equals(f.caseId())) {
            throw new IllegalArgumentException("Finding belongs to case " + f.caseId() + ", not " + caseId);
        }
        if (f.supersedes() != null && findings.stream().noneMatch(x -> x.findingId().equals(f.supersedes()))) {
            throw new IllegalArgumentException("supersedes unknown finding " + f.supersedes());
        }
        findings.add(f);
        if (activity != null) {
            activity.finding(f);
        }
        return f;
    }

    @Override
    public synchronized List<Finding> findings() {
        return List.copyOf(findings);
    }

    @Override
    public synchronized Optional<Finding> latest(String agentId, Finding.Kind kind) {
        for (int i = findings.size() - 1; i >= 0; i--) {
            Finding f = findings.get(i);
            if (f.agentId().equals(agentId) && f.kind() == kind) {
                return Optional.of(f);
            }
        }
        return Optional.empty();
    }

    @Override
    public synchronized List<Finding> currentView() {
        Map<String, Finding> latest = new LinkedHashMap<>();
        for (Finding f : findings) {
            latest.put(f.agentId() + "/" + f.kind(), f);
        }
        return List.copyOf(latest.values());
    }

    @Override
    public synchronized AgentMessage post(AgentMessage m) {
        AgentMessage stored = new AgentMessage(m.messageId() == null ? UUID.randomUUID().toString() : m.messageId(),
                caseId, m.traceId(), messages.size() + 1L, m.from(), m.to(), m.type(), m.refs(), m.payload(),
                m.createdAt() == null ? Instant.now() : m.createdAt(), 1);
        messages.add(stored);
        if (activity != null) {
            activity.message(flow, stored);
        }
        return stored;
    }

    @Override
    public synchronized List<AgentMessage> messages() {
        return List.copyOf(messages);
    }

    @Override
    public synchronized boolean injectionSuspected() {
        return findings.stream().anyMatch(Finding::injectionSuspect);
    }

    @Override
    public synchronized CaseState copy() {
        InMemoryCaseState c = new InMemoryCaseState(caseId, flow, activity);
        c.findings.addAll(findings);
        c.messages.addAll(messages);
        return c;
    }
}
