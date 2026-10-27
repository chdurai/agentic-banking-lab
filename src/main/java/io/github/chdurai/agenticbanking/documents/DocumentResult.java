package io.github.chdurai.agenticbanking.documents;

import java.time.Instant;
import java.util.List;

public record DocumentResult(
        String documentId,
        Status status,
        String workflowQueue,
        DocumentAnalysis analysis,
        int chunksProcessed,
        List<String> flags,
        String approvalId,
        Instant processedAt,
        java.util.Map<String, io.github.chdurai.agenticbanking.rag.GroundingVerifier.SourceSpan> sourceSpans,
        String sourceTextSha256
) {
    public DocumentResult {
        sourceSpans = sourceSpans == null ? java.util.Map.of() : java.util.Map.copyOf(sourceSpans);
    }

    public DocumentResult(String documentId, Status status, String workflowQueue, DocumentAnalysis analysis,
                          int chunksProcessed, List<String> flags, String approvalId, Instant processedAt) {
        this(documentId, status, workflowQueue, analysis, chunksProcessed, flags, approvalId, processedAt, java.util.Map.of(), null);
    }

    public enum Status { ANALYZED, NEEDS_REVIEW, BLOCKED }
}
