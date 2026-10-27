package io.github.chdurai.agenticbanking.approval;

import java.time.Instant;

/** Independent reviewer assessment captured before showing AI recommendations. */
public record BlindFirstAssessment(
        String reviewerId,
        Decision decision,
        String reasonCode,
        String note,
        Instant assessedAt
) {
    public enum Decision { APPROVE, REJECT, OVERRIDE }
}
