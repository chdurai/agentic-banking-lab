package io.github.chdurai.agenticbanking.grievance;

import jakarta.validation.constraints.NotBlank;

/**
 * An incoming grievance from a project-affected person or worker.
 * Free text may be in any language the model supports.
 */
public record Grievance(
        @NotBlank String grievanceId,
        @NotBlank String projectId,
        @NotBlank String description,
        String channel,       // WEB | SMS | PHONE | COMMUNITY_MEETING | LETTER
        String location,
        boolean anonymous
) {}
