package io.github.chdurai.agenticbanking.cases;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** Atomic demo store with versioning and idempotency; not durable across process restarts. */
public class InMemoryCaseStore implements CaseStore {

    private final Map<String, CaseRecord> cases = new LinkedHashMap<>();
    private final Map<String, String> byKey = new LinkedHashMap<>();
    private final Map<String, List<CaseEvent>> events = new LinkedHashMap<>();
    private final Map<Long, OutboxMessage> outbox = new LinkedHashMap<>();
    private final AtomicLong outboxIds = new AtomicLong();
    private final Map<String, CaseLink> links = new LinkedHashMap<>();

    @Override
    public synchronized Claim claim(String flow, String idempotencyKey, String requestHash, String subjectRef) {
        return enqueue(flow, idempotencyKey, requestHash, subjectRef, null);
    }

    @Override
    public synchronized Claim enqueue(String flow, String idempotencyKey, String requestHash, String subjectRef, String requestJson) {
        String k = io.github.chdurai.agenticbanking.security.CallerContext.key(flow + "|" + idempotencyKey);
        String existingId = byKey.get(k);
        if (existingId != null) {
            CaseRecord existing = cases.get(existingId);
            if (!existing.requestHash().equals(requestHash)) {
                throw new CaseStoreExceptions.IdempotencyConflictException(
                        "Idempotency-Key reused with a different request body");
            }
            return new Claim(existing, false);
        }
        Instant now = Instant.now();
        CaseRecord rec = new CaseRecord(UUID.randomUUID().toString(), flow, subjectRef, idempotencyKey,
                requestHash, CaseStatus.OPEN, 0, null, now, now,
                io.github.chdurai.agenticbanking.security.CallerContext.current().tenantId(), requestJson);
        cases.put(rec.caseId(), rec);
        byKey.put(k, rec.caseId());
        events.computeIfAbsent(rec.caseId(), x -> new ArrayList<>())
                .add(new CaseEvent(rec.caseId(), 1, "case.opened", "system", null, now));
        return new Claim(rec, true);
    }

    @Override
    public synchronized CaseRecord transition(String caseId, long expectedVersion, CaseStatus status,
                                              String resultJson, String actor, String eventType,
                                              OutboxMessage out) {
        CaseRecord cur = find(caseId).orElse(null);
        if (cur == null) {
            throw new IllegalArgumentException("Unknown case " + caseId);
        }
        if (cur.version() != expectedVersion) {
            throw new CaseStoreExceptions.StaleVersionException(
                    "Case %s is at version %d, expected %d".formatted(caseId, cur.version(), expectedVersion));
        }
        Instant now = Instant.now();
        CaseRecord next = new CaseRecord(cur.caseId(), cur.flow(), cur.subjectRef(), cur.idempotencyKey(),
                cur.requestHash(), status, cur.version() + 1, resultJson != null ? resultJson : cur.resultJson(),
                cur.createdAt(), now, cur.tenantId(), cur.requestJson());
        cases.put(caseId, next);
        List<CaseEvent> list = events.computeIfAbsent(caseId, x -> new ArrayList<>());
        list.add(new CaseEvent(caseId, list.size() + 1, eventType, actor, resultJson, now));
        if (out != null) {
            long id = outboxIds.incrementAndGet();
            outbox.put(id, new OutboxMessage(id, caseId, out.type(), out.flow(), out.subjectRef(),
                    out.payloadJson(), 0, now));
        }
        return next;
    }

    @Override
    public synchronized Optional<CaseRecord> find(String caseId) {
        return Optional.ofNullable(cases.get(caseId))
                .filter(r -> r.tenantId().equals(io.github.chdurai.agenticbanking.security.CallerContext.current().tenantId()));
    }

    @Override
    public synchronized List<CaseEvent> events(String caseId) {
        return find(caseId).isEmpty() ? List.of() : List.copyOf(events.getOrDefault(caseId, List.of()));
    }

    @Override
    public synchronized List<OutboxMessage> pendingOutbox(int limit) {
        return outbox.values().stream().filter(m -> find(m.caseId()).isPresent()).limit(limit).toList();
    }

    @Override
    public synchronized void markPublished(long outboxId) {
        OutboxMessage row = outbox.get(outboxId);
        if (row != null && find(row.caseId()).isPresent()) outbox.remove(outboxId);
    }

    @Override
    public synchronized void markFailed(long outboxId) {
        OutboxMessage m = outbox.get(outboxId);
        if (m != null && find(m.caseId()).isPresent()) {
            outbox.put(outboxId, new OutboxMessage(m.id(), m.caseId(), m.type(), m.flow(), m.subjectRef(),
                    m.payloadJson(), m.attempts() + 1, m.createdAt()));
        }
    }

    @Override
    public synchronized long countCases() {
        return cases.values().stream().filter(r -> find(r.caseId()).isPresent()).count();
    }

    @Override public synchronized List<CaseRecord> queued(int limit) {
        return scoped().filter(r -> r.status() == CaseStatus.OPEN && r.requestJson() != null).limit(limit).toList();
    }
    @Override public synchronized long countQueued() {
        return scoped().filter(r -> r.status() == CaseStatus.OPEN && r.requestJson() != null).count();
    }
    @Override public synchronized List<CaseRecord> running(int limit) {
        return scoped().filter(r -> r.status() == CaseStatus.RUNNING).limit(limit).toList();
    }
    @Override public synchronized List<CaseRecord> page(String afterId, int limit) {
        return scoped().filter(r -> afterId == null || r.caseId().compareTo(afterId) > 0)
                .sorted(java.util.Comparator.comparing(CaseRecord::caseId)).limit(limit).toList();
    }
    private java.util.stream.Stream<CaseRecord> scoped() {
        return cases.values().stream().filter(r -> r.tenantId().equals(io.github.chdurai.agenticbanking.security.CallerContext.current().tenantId()));
    }

    @Override public synchronized CaseLink.Creation link(String fromCase, String toCase, CaseLink.Type type) {
        var source = find(fromCase).orElseThrow(() -> new IllegalArgumentException("Link endpoint unavailable"));
        var target = find(toCase).orElseThrow(() -> new IllegalArgumentException("Link endpoint unavailable"));
        if (type == null || !type.sourceFlow().equals(source.flow()) || !type.targetFlow().equals(target.flow())) {
            throw new IllegalArgumentException("Link type does not match its endpoint flows");
        }
        var existing = links.values().stream().filter(l -> l.fromCase().equals(fromCase)
                && l.toCase().equals(toCase) && l.type() == type).findFirst();
        if (existing.isPresent()) return new CaseLink.Creation(existing.get(), false);
        var caller = io.github.chdurai.agenticbanking.security.CallerContext.current();
        var link = new CaseLink(UUID.randomUUID().toString(), caller.tenantId(), fromCase, toCase, type,
                caller.actor(), Instant.now());
        links.put(link.id(), link);
        long messageId = outboxIds.incrementAndGet();
        outbox.put(messageId, new OutboxMessage(messageId, fromCase, "case.linked", source.flow(), source.subjectRef(),
                "{\"linkId\":\"" + link.id() + "\"}", 0, link.createdAt()));
        return new CaseLink.Creation(link, true);
    }

    @Override public synchronized Optional<CaseLink> findLink(String id) {
        return Optional.ofNullable(links.get(id)).filter(l -> find(l.fromCase()).isPresent() && find(l.toCase()).isPresent());
    }

    @Override public synchronized List<CaseLink> links(String caseId, String afterId, int limit) {
        if (find(caseId).isEmpty()) return List.of();
        return links.values().stream().filter(l -> l.fromCase().equals(caseId) || l.toCase().equals(caseId))
                .filter(l -> findLink(l.id()).isPresent()).filter(l -> afterId == null || l.id().compareTo(afterId) > 0)
                .sorted(java.util.Comparator.comparing(CaseLink::id)).limit(limit).toList();
    }
}
