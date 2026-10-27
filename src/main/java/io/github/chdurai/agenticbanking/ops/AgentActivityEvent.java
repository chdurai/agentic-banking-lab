package io.github.chdurai.agenticbanking.ops;

import java.time.Instant;

/**
 * One observable unit of agent activity, streamed to the Mission Control UI.
 *
 * @param seq        global monotone sequence (assigned by {@link AgentActivityHub})
 * @param kind       coarse category the UI uses to route the event
 * @param flow       loan | incident | grievance | document | platform
 * @param caseId     case / application / correlation id, when known
 * @param agent      agent that acted (sender, for {@link Kind#MESSAGE})
 * @param to         recipient agent, for {@link Kind#MESSAGE}
 * @param label      short human label (outcome, message type, event type)
 * @param detail     longer text (finding narrative, payload excerpt); may be null
 * @param durationMs execution time, for {@link Kind#AGENT_COMPLETED} and {@link Kind#LLM}
 * @param status     PASS | WARN | FAIL | UNAVAILABLE | TIMEOUT | ERROR | OK, when applicable
 * @param request    for {@link Kind#LLM}: provider request JSON or agent prompt (excerpt)
 * @param response   for {@link Kind#LLM}: provider response JSON or parsed agent result (excerpt)
 * @param at         event time
 */
public record AgentActivityEvent(
        long seq,
        Kind kind,
        String flow,
        String caseId,
        String agent,
        String to,
        String label,
        String detail,
        Long durationMs,
        String status,
        String request,
        String response,
        Instant at) {

    public enum Kind { AGENT_STARTED, AGENT_COMPLETED, FINDING, MESSAGE, FLOW, LLM }
}
