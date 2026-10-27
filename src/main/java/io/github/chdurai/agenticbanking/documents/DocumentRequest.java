package io.github.chdurai.agenticbanking.documents;

import jakarta.validation.constraints.NotBlank;

/** A document submitted for analysis. OCR extraction is delegated via {@link DocumentOcrPort}. */
public record DocumentRequest(
        @NotBlank String documentId,
        String title,
        @NotBlank String content
) {}
