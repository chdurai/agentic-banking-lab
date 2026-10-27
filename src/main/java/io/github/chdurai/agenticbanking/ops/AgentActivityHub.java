package io.github.chdurai.agenticbanking.ops;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import io.github.chdurai.agenticbanking.flow.state.AgentMessage;
import io.github.chdurai.agenticbanking.flow.state.Finding;
import io.github.chdurai.agenticbanking.llm.LlmResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * In-process bus for live agent activity. Producers (flow runtime, case state, LLM gateway, platform
 * events) emit; the observability controller subscribes and fans out to SSE clients. Delivery to
 * subscribers is asynchronous so a slow client can never stall an agent: a single daemon thread drains
 * a bounded queue, dropping the oldest pending dispatch under sustained overload (telemetry, not audit).
 *
 * <p>Microservices seam: swap the subscriber fan-out for a Kafka/websocket bridge without touching
 * the producers.
 */
@Component
public class AgentActivityHub {

    private static final Logger log = LoggerFactory.getLogger(AgentActivityHub.class);
    private static final int CAPACITY = 600;
    private static final int DISPATCH_QUEUE = 4096;
    private static final int DETAIL_LIMIT = 280;
    private static final int PAYLOAD_LIMIT = 2400;
    private static final int PROVIDER_PAYLOAD_LIMIT = 32768;

    private final ConcurrentLinkedDeque<AgentActivityEvent> backlog = new ConcurrentLinkedDeque<>();
    private final CopyOnWriteArrayList<Consumer<AgentActivityEvent>> subscribers = new CopyOnWriteArrayList<>();
    private final AtomicLong seq = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    /** Serializes sequence allocation with backlog insertion so seq order == publication order. */
    private final Object sequencer = new Object();
    private final ObjectMapper json = new ObjectMapper();
    private final ThreadPoolExecutor dispatcher = new ThreadPoolExecutor(1, 1,
            0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(DISPATCH_QUEUE),
            r -> {
                Thread t = new Thread(r, "agent-activity-dispatch");
                t.setDaemon(true);
                return t;
            },
            (task, pool) -> {
                // Telemetry policy: drop the oldest undelivered dispatch, keep the newest event flowing.
                // Never recurse into execute(); offer once after making room, else drop the new task too.
                pool.getQueue().poll();
                if (!pool.isShutdown()) {
                    pool.getQueue().offer(task);
                }
                long drops = dropped.incrementAndGet();
                if (drops == 1 || drops % 100 == 0) {
                    log.warn("Agent activity dispatch queue full; dropping oldest events ({} dropped so far)",
                            drops);
                }
            });

    /** Subscribes to live events; returns an unsubscribe handle. */
    public Runnable subscribe(Consumer<AgentActivityEvent> listener) {
        subscribers.add(listener);
        return () -> subscribers.remove(listener);
    }

    /**
     * Subscribes and replays backlog events with {@code seq > afterSeq} (oldest-first, up to
     * {@code limit}) exactly once: the subscription is registered under the sequencer lock, live
     * deliveries during replay are tracked, and the replay runs on the same ordered dispatcher as
     * live traffic so events cannot interleave or duplicate. Returns an unsubscribe handle.
     */
    public Runnable subscribeWithReplay(Consumer<AgentActivityEvent> listener, long afterSeq, int limit) {
        List<AgentActivityEvent> missed;
        java.util.concurrent.atomic.AtomicBoolean active = new java.util.concurrent.atomic.AtomicBoolean(true);
        java.util.Set<Long> liveDuringReplay = java.util.concurrent.ConcurrentHashMap.newKeySet();
        Consumer<AgentActivityEvent> tracking = e -> {
            if (active.get()) {
                liveDuringReplay.add(e.seq());
                listener.accept(e);
            }
        };
        synchronized (sequencer) {
            // Under the same lock that emit() uses for backlog + dispatch enqueue: every pre-snapshot
            // event's live dispatch is already queued ahead of the replay task, and every post-snapshot
            // event is queued after it. The single-thread dispatcher preserves that order.
            subscribers.add(tracking);
            missed = backlog.stream()
                    .filter(e -> e.seq() > afterSeq)
                    .sorted(java.util.Comparator.comparingLong(AgentActivityEvent::seq))
                    .limit(Math.max(0, limit))
                    .toList();
            dispatcher.execute(() -> {
                try {
                    for (AgentActivityEvent e : missed) {
                        if (active.get() && !liveDuringReplay.contains(e.seq())) {
                            listener.accept(e);
                        }
                    }
                } finally {
                    // Swap to the plain listener only if the subscription is still alive.
                    if (subscribers.remove(tracking) && active.get()) {
                        subscribers.add(listener);
                    }
                }
            });
        }
        return () -> {
            active.set(false);
            subscribers.remove(tracking);
            subscribers.remove(listener);
        };
    }

    /** Most-recent-first snapshot for client backfill. */
    public List<AgentActivityEvent> recent(int limit) {
        List<AgentActivityEvent> out = new ArrayList<>();
        var it = backlog.descendingIterator();
        while (it.hasNext() && out.size() < limit) {
            out.add(it.next());
        }
        return out;
    }

    /** Highest sequence assigned so far; 0 when idle. Used for replay/live handoff. */
    public long currentSeq() {
        return seq.get();
    }

    public void agentStarted(String flow, String caseId, String agent) {
        emit(AgentActivityEvent.Kind.AGENT_STARTED, flow, caseId, agent, null, "started", null, null, null,
                null, null);
    }

    public void agentCompleted(String flow, String caseId, String agent, long durationMs,
                               String outcome, String status) {
        emit(AgentActivityEvent.Kind.AGENT_COMPLETED, flow, caseId, agent, null, outcome, null, durationMs,
                status, null, null);
    }

    /** Agent-to-agent protocol traffic (CHALLENGE, FINDING_PUBLISHED, INFO_REQUEST, …). */
    public void message(String flow, AgentMessage m) {
        emit(AgentActivityEvent.Kind.MESSAGE, flow, m.caseId(), m.from(), m.to(),
                m.type().name(), summarizePayload(m), null, null, null, null);
    }

    /** An agent appended a typed finding to the case blackboard. */
    public void finding(Finding f) {
        emit(AgentActivityEvent.Kind.FINDING, f.flow(), f.caseId(), f.agentId(), null,
                f.kind().name(), truncate(f.narrative(), DETAIL_LIMIT), null, f.status().name(), null, null);
    }

    public void providerExchange(ProviderExchangeContext context, String model, String request,
                                 String response, int httpStatus, long durationMs) {
        emit(AgentActivityEvent.Kind.LLM, context.flow(), context.caseId(), context.agent(), null,
                "provider · " + model, "HTTP " + httpStatus + " · actual provider exchange",
                durationMs, httpStatus >= 400 ? "ERROR" : "OK",
                truncate(request, PROVIDER_PAYLOAD_LIMIT), truncate(response, PROVIDER_PAYLOAD_LIMIT));
    }

    /** A parsed agent result, distinct from the actual provider exchange. */
    public void llmExchange(String flow, String caseId, String agent, String system, String user,
                            LlmResult<?> result) {
        String detail = result.promptTokens() + "→" + result.completionTokens() + " tok"
                + (result.fallbackUsed() ? " · fallback" : "")
                + String.format(" · $%.4f", result.costUsd());
        emit(AgentActivityEvent.Kind.LLM, flow, caseId, agent, null,
                result.model(), detail, result.latencyMs(), "OK",
                truncate(system + "\n\n--- user ---\n\n" + user, PAYLOAD_LIMIT),
                truncate(toJson(result.value()), PAYLOAD_LIMIT));
    }

    /** A failed LLM call; the prompt is still captured so the failure is debuggable from the UI. */
    public void llmFailure(String flow, String caseId, String agent, String system, String user,
                           long latencyMs, Throwable error) {
        emit(AgentActivityEvent.Kind.LLM, flow, caseId, agent, null,
                "call failed", error == null ? null : error.getMessage(), latencyMs, "ERROR",
                truncate(system + "\n\n--- user ---\n\n" + user, PAYLOAD_LIMIT), null);
    }

    /** Business lifecycle events (loan.decided, incident.resolved, …) join the same stream. */
    @EventListener
    public void onPlatformEvent(PlatformEvent e) {
        emit(AgentActivityEvent.Kind.FLOW, e.flow(), e.subjectId(), null, null, e.type(), e.summary(),
                null, null, null, null);
    }

    private void emit(AgentActivityEvent.Kind kind, String flow, String caseId, String agent, String to,
                      String label, String detail, Long durationMs, String status,
                      String request, String response) {
        synchronized (sequencer) {
            AgentActivityEvent event = new AgentActivityEvent(seq.incrementAndGet(), kind,
                    flow == null ? "platform" : flow, caseId, agent, to, label, detail, durationMs, status,
                    request, response, Instant.now());
            backlog.addLast(event);
            while (backlog.size() > CAPACITY) {
                backlog.pollFirst();
            }
            // Enqueued under the same lock so dispatch order always matches sequence order,
            // and replay tasks (subscribeWithReplay) linearize correctly against live events.
            List<Consumer<AgentActivityEvent>> listeners = List.copyOf(subscribers);
            try {
                dispatcher.execute(() -> {
                    for (var listener : listeners) {
                        try {
                            listener.accept(event);
                        } catch (Exception e) {
                            subscribers.remove(listener);
                        }
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException shutdown) {
                // Hub is shutting down; observability must never break the producing flow.
            }
        }
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return json.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private static String summarizePayload(AgentMessage m) {
        if (m.payload().isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        m.payload().forEach((k, v) -> {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(k).append(": ").append(v);
        });
        return truncate(sb.toString(), DETAIL_LIMIT);
    }

    private static String truncate(String text, int limit) {
        if (text == null) {
            return null;
        }
        return text.length() <= limit ? text : text.substring(0, limit - 1) + "…";
    }

    @PreDestroy
    void shutdown() {
        dispatcher.shutdownNow();
    }
}
