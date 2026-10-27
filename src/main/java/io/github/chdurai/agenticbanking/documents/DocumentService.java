package io.github.chdurai.agenticbanking.documents;

import io.github.chdurai.agenticbanking.approval.ApprovalEvidencePacket;
import io.github.chdurai.agenticbanking.approval.ApprovalQueue;
import io.github.chdurai.agenticbanking.guardrails.InputGuardrail;
import io.github.chdurai.agenticbanking.ops.AgentMetrics;
import io.github.chdurai.agenticbanking.ops.BoundedHistory;
import io.github.chdurai.agenticbanking.ops.ExecutionTraceStore;
import io.github.chdurai.agenticbanking.ops.PlatformEvent;
import io.github.chdurai.agenticbanking.ops.PlatformEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Document pipeline: guardrails (injection screening, SSN/card masking) → analysis
 * (map-reduce for long docs) → deterministic workflow routing. The LLM classifies and
 * extracts; which queue receives the document is policy code. Low-confidence or
 * unclassifiable documents go to a human.
 */
@Service
public class DocumentService {

    static final int MAX_CHARS = 100_000;
    static final int MIN_CONFIDENCE = 60;

    private final DocumentAnalysisAgent agent;
    private final DocumentOcrPort ocr;
    private final PdfTextExtractor pdfs;
    private final InputGuardrail guardrail;
    private final ApprovalQueue approvals;
    private final AgentMetrics metrics;
    private final ExecutionTraceStore traces;
    private final PlatformEventPublisher events;
    @org.springframework.beans.factory.annotation.Autowired
    private io.github.chdurai.agenticbanking.llm.LlmProperties llm = io.github.chdurai.agenticbanking.llm.LlmProperties.defaults();
    private final BoundedHistory<DocumentResult> history = new BoundedHistory<>(200);

    public DocumentService(DocumentAnalysisAgent agent, DocumentOcrPort ocr, PdfTextExtractor pdfs,
                           InputGuardrail guardrail, ApprovalQueue approvals,
                           AgentMetrics metrics, ExecutionTraceStore traces, PlatformEventPublisher events) {
        this.agent = agent;
        this.ocr = ocr;
        this.pdfs = pdfs;
        this.guardrail = guardrail;
        this.approvals = approvals;
        this.metrics = metrics;
        this.traces = traces;
        this.events = events;
    }

    public DocumentResult analyze(DocumentRequest doc) {
        try (var ignored = llm.budget().openForFlow("document")) {
            return analyzeWithinBudget(doc, List.of());
        }
    }

    public DocumentResult analyzePdf(String documentId, String title, byte[] bytes) {
        try (var ignored = llm.budget().openForFlow("document")) {
            var extracted = pdfs.extract(bytes);
            var doc = new DocumentRequest(documentId, title, extracted.text());
            if (extracted.text().isBlank()) {
                var screenedTitle = guardrail.screenText("title", title);
                if (screenedTitle.blocked()) {
                    metrics.recordGuardrailBlock("document");
                    return finish(new DocumentResult(documentId, DocumentResult.Status.BLOCKED, "none", null, 0,
                            screenedTitle.flags(), null, Instant.now()));
                }
                List<String> flags = new ArrayList<>(extracted.flags());
                flags.addAll(screenedTitle.flags());
                List<String> details = List.of("PDF text extraction incomplete; reviewed OCR or manual extraction required.");
                String approvalId = approvals.submit("document", documentId, "PDF extraction requires review", details,
                        documentEvidencePacket(new DocumentRequest(documentId, screenedTitle.sanitized(), ""),
                                null, flags, details, "Manual document extraction required")).id();
                return finish(new DocumentResult(documentId, DocumentResult.Status.NEEDS_REVIEW, "manual-triage",
                        null, 0, flags, approvalId, Instant.now()));
            }
            return analyzeWithinBudget(doc, extracted.flags());
        }
    }

    private DocumentResult analyzeWithinBudget(DocumentRequest doc, List<String> extractionFlags) {
        List<String> flags = new ArrayList<>(extractionFlags);
        var screened = guardrail.screenText("content", doc.content(), MAX_CHARS);
        var title = guardrail.screenText("title", doc.title());
        flags.addAll(screened.flags());
        flags.addAll(title.flags());
        if (screened.blocked() || title.blocked()) {
            metrics.recordGuardrailBlock("document");
            return finish(new DocumentResult(doc.documentId(), DocumentResult.Status.BLOCKED, "none", null, 0,
                    flags, null, Instant.now()));
        }

        String ocrText;
        try {
            ocrText = ocr.extractText(new DocumentRequest(doc.documentId(), title.sanitized(), screened.sanitized()));
            if (ocrText == null || ocrText.isBlank()) {
                throw new IllegalStateException("OCR returned no document text");
            }
        } catch (RuntimeException e) {
            flags.add("OCR_UNAVAILABLE");
            List<String> details = List.of("Reason: OCR extraction failed - " + e.getMessage(),
                    "Title: " + title.sanitized());
            String approvalId = approvals.submit("document", doc.documentId(),
                    "Document OCR unavailable - manual triage",
                    details,
                    documentEvidencePacket(new DocumentRequest(doc.documentId(), title.sanitized(), screened.sanitized()),
                            null, flags, details, "OCR extraction unavailable; manual triage required")).id();
            return finish(new DocumentResult(doc.documentId(), DocumentResult.Status.NEEDS_REVIEW,
                    "manual-triage", null, 0, flags, approvalId, Instant.now()));
        }
        var extracted = guardrail.screenText("ocrContent", ocrText, MAX_CHARS);
        flags.addAll(extracted.flags());
        if (extracted.blocked()) {
            metrics.recordGuardrailBlock("document");
            return finish(new DocumentResult(doc.documentId(), DocumentResult.Status.BLOCKED, "none", null, 0,
                    flags, null, Instant.now()));
        }
        DocumentRequest clean = new DocumentRequest(doc.documentId(), title.sanitized(), extracted.sanitized());
        long start = System.nanoTime();
        DocumentAnalysisAgent.Analyzed analyzed;
        try {
            analyzed = agent.analyze(clean, clean.content(), flags);
            traces.record("document", doc.documentId(), "document-analysis",
                    (System.nanoTime() - start) / 1_000_000, "OK");
        } catch (RuntimeException e) {
            // Fail safe: an unavailable analyzer becomes manual triage, never a 500.
            traces.record("document", doc.documentId(), "document-analysis",
                    (System.nanoTime() - start) / 1_000_000, "ERROR: " + e.getMessage());
            flags.add("ANALYZER_UNAVAILABLE");
            List<String> details = List.of("Reason: " + e.getMessage(), "Title: " + clean.title());
            String approvalId = approvals.submit("document", doc.documentId(),
                    "Document analysis unavailable - manual triage",
                    details,
                    documentEvidencePacket(clean, null, flags, details,
                            "Analyzer unavailable; manual triage required")).id();
            return finish(new DocumentResult(doc.documentId(), DocumentResult.Status.NEEDS_REVIEW,
                    "manual-triage", null, 0, flags, approvalId, Instant.now()));
        }

        DocumentAnalysis a = analyzed.analysis();
        boolean truncated = flags.stream().anyMatch(f -> f.startsWith("DOCUMENT_TRUNCATED_TO_")
                || f.startsWith("overlong field truncated:"));
        boolean ungrounded = flags.stream().anyMatch(f -> f.startsWith("GROUNDING_FAIL:"));
        boolean piiDetected = a.containsPii() || flags.stream().anyMatch(f -> f.startsWith("full SSN masked")
                || f.startsWith("card number masked"));
        boolean incompleteExtraction = !extractionFlags.isEmpty();
        boolean review = incompleteExtraction || truncated || ungrounded || piiDetected
                || a.documentType() == null || a.documentType() == DocumentAnalysis.DocType.OTHER
                || a.confidencePct() < MIN_CONFIDENCE;
        String queue = piiDetected ? "restricted-review" : review ? "manual-triage" : queueFor(a.documentType());

        String approvalId = null;
        if (review) {
            String reason = incompleteExtraction ? "PDF extraction incomplete; requires manual review"
                    : truncated ? "analysis truncated; requires manual review"
                    : ungrounded ? "extracted fields are not grounded in the source"
                    : piiDetected ? "personal data requires restricted review"
                    : "low confidence or unclassified";
            List<String> details = List.of("Summary: " + a.summary(), "Reason: " + reason);
            approvalId = approvals.submit("document", doc.documentId(),
                    "Document needs manual triage (" + a.documentType() + ", " + a.confidencePct() + "%)",
                    details,
                    documentEvidencePacket(clean, a, flags, details, "Manual review required")).id();
        }
        return finish(new DocumentResult(doc.documentId(),
                review ? DocumentResult.Status.NEEDS_REVIEW : DocumentResult.Status.ANALYZED, queue, a,
                analyzed.chunks(), flags, approvalId, Instant.now(), analyzed.sourceSpans(), analyzed.sourceTextSha256()));
    }

    public List<DocumentResult> recent(int limit) {
        return history.recent(limit);
    }

    static String queueFor(DocumentAnalysis.DocType type) {
        return switch (type) {
            case LOAN_AGREEMENT -> "loan-servicing";
            case INVOICE -> "accounts-payable";
            case IDENTITY_DOCUMENT -> "kyc-review";
            case BANK_STATEMENT -> "underwriting";
            case PROJECT_REPORT -> "knowledge-management";
            case CORRESPONDENCE -> "customer-support";
            case OTHER -> "manual-triage";
        };
    }

    private DocumentResult finish(DocumentResult result) {
        history.add(result);
        metrics.recordCounter("document.processed", "status", result.status().name());
        events.publish(PlatformEvent.of("document.processed", "document", result.documentId(),
                result.status() + " -> " + result.workflowQueue()));
        return result;
    }

    private static ApprovalEvidencePacket documentEvidencePacket(DocumentRequest doc, DocumentAnalysis analysis,
                                                                 List<String> flags, List<String> details,
                                                                 String recommendation) {
        List<ApprovalEvidencePacket.AgentFinding> findings = analysis == null
                ? List.of(new ApprovalEvidencePacket.AgentFinding("document-analysis", "UNAVAILABLE", 80,
                "Analyzer unavailable", List.of()))
                : List.of(new ApprovalEvidencePacket.AgentFinding("document-analysis", "PASS", analysis.confidencePct(),
                analysis.summary(), analysis.actionItems()));
        int maxRisk = analysis == null ? 80 : (analysis.confidencePct() < MIN_CONFIDENCE ? 70 : 35);
        List<String> uncertainty = new ArrayList<>();
        if (analysis == null) {
            uncertainty.add("Analyzer unavailable");
        } else if (analysis.confidencePct() < MIN_CONFIDENCE) {
            uncertainty.add("Low extraction confidence: " + analysis.confidencePct() + "%");
        }
        if (flags != null && !flags.isEmpty()) {
            uncertainty.add("Guardrail flags: " + String.join(", ", flags));
        }
        ApprovalEvidencePacket.SideEffectPreview sideEffects = new ApprovalEvidencePacket.SideEffectPreview(
                List.of("Approve routes document to queue " + (analysis == null ? "manual-triage" : queueFor(analysis.documentType()))),
                List.of("Reject keeps document in manual-triage queue."));
        boolean nonTrivial = maxRisk >= 60 || !uncertainty.isEmpty();
        return new ApprovalEvidencePacket(1, "document", doc.documentId(),
                List.of("documentId=" + doc.documentId(), "title=" + doc.title()),
                findings, List.of(), details, "n/a", List.of("document-analysis:v1"),
                sideEffects, uncertainty, recommendation, maxRisk, nonTrivial);
    }
}
