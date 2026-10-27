package io.github.chdurai.agenticbanking.incident;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record Incident(
        @NotBlank String incidentId,
        @NotBlank String title,
        @NotBlank String description,
        @NotBlank String service,
        String environment,
        String reporter,
        List<String> tags
) {}
