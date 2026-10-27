package io.github.chdurai.agenticbanking.approval;

import io.github.chdurai.agenticbanking.ops.PlatformEvent;
import io.github.chdurai.agenticbanking.ops.PlatformEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Resumes a loan that was parked for human review: emits the final decision event and, for a decline, the
 * adverse-action notice. Compliance holds never produce customer-facing events (anti tipping-off).
 */
@Component
public class LoanDecisionResumeListener implements ApprovalOutcomeListener {

    private final PlatformEventPublisher events;

    public LoanDecisionResumeListener(PlatformEventPublisher events) {
        this.events = events;
    }

    @Override
    public void onOutcome(ApprovalRequest r) {
        if (!"loan".equals(r.flow())) {
            return;
        }
        switch (r.status()) {
            case APPROVED, OVERRIDDEN -> events.publish(PlatformEvent.of("loan.final", "loan", r.subjectId(),
                    "%s by %s".formatted(r.status() == ApprovalRequest.Status.APPROVED ? "APPROVED" : "OVERRIDDEN: " + r.note(),
                            r.decidedBy())));
            case REJECTED -> {
                events.publish(PlatformEvent.of("loan.final", "loan", r.subjectId(), "DECLINED by " + r.decidedBy()));
                events.publish(PlatformEvent.of("loan.adverse_action", "loan", r.subjectId(),
                        "Adverse action notice due after human review"));
            }
            case EXPIRED -> events.publish(PlatformEvent.of("loan.final", "loan", r.subjectId(),
                    "Review expired; kept in MANUAL_REVIEW, no approval granted"));
            default -> { }
        }
    }
}
