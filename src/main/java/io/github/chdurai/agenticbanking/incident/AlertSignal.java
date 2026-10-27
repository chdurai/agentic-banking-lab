package io.github.chdurai.agenticbanking.incident;

import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.List;

/** External alert payload for webhook-based ingestion (WI-11). */
public record AlertSignal(
        @NotBlank String title,
        @NotBlank String description,
        @NotBlank String service,
        String environment,
        String source,
        String symptomKey,
        List<String> tags,
        Instant observedAt
) {}
