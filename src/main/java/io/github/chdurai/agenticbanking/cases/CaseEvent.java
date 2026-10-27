package io.github.chdurai.agenticbanking.cases;

import java.time.Instant;

/** Append-only audit fact about a case. */
public record CaseEvent(String caseId, long seq, String type, String actor, String payloadJson, Instant at) {
}
