package io.github.chdurai.agenticbanking.grievance;

import java.util.List;

/** Draft reply and action plan for the case officer to review - never auto-sent for high-risk cases. */
public record GrievanceResponseDraft(
        String acknowledgement,
        List<String> proposedActions,
        List<String> citedPlaybooks,
        String followUpPlan
) {}
