package io.github.chdurai.agenticbanking.documents;

import io.github.chdurai.agenticbanking.llm.AgentCallSpec;
import io.github.chdurai.agenticbanking.llm.LlmGateway;
import io.github.chdurai.agenticbanking.guardrails.Spotlight;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Document intelligence: classification, key-field extraction and summarization.
 * Documents over {@link #CHUNK_CHARS} are map-reduced: chunks are summarized in parallel
 * (virtual threads), then the analysis runs over the condensed summaries.
 */
@Component
public class DocumentAnalysisAgent {

    private static final Logger log = LoggerFactory.getLogger(DocumentAnalysisAgent.class);
    static final String NAME = "document-analysis";
    static final String CHUNK_NAME = "document-chunk";
    static final int CHUNK_CHARS = 6000;
    static final int MAX_CHUNKS = 12;

    private static final String SYSTEM = """
            You are a document intelligence engine for a bank and development-finance back office.
            Analyze the document and return:
            - documentType: LOAN_AGREEMENT, INVOICE, IDENTITY_DOCUMENT, BANK_STATEMENT, PROJECT_REPORT,
              CORRESPONDENCE or OTHER.
            - summary: at most 3 sentences.
            - keyFields: important fields as name -> value (parties, amounts, dates, reference numbers).
              Only include values literally present in the document; never guess.
            - actionItems: follow-ups the document requires, if any.
            - containsPii: true if it contains personal identifiers (names with ID numbers, addresses, account numbers).
            - confidencePct: 0-100 honest confidence in the classification and extraction.
            """ + Spotlight.INSTRUCTION;

    private static final String CHUNK_SYSTEM = """
            You are a document-chunk summarizer. Summarize this part of a longer document in at most
            4 sentences and list the key facts (parties, amounts, dates, obligations) literally present in it.
            """ + Spotlight.INSTRUCTION;

    private final LlmGateway llm;
    private final io.github.chdurai.agenticbanking.capability.CapabilityGuard capabilities;

    public DocumentAnalysisAgent(LlmGateway llm, io.github.chdurai.agenticbanking.capability.CapabilityGuard capabilities) {
        this.llm = llm;
        this.capabilities = capabilities;
    }

    /** @return the analysis and the number of chunks summarized (0 for short documents) */
    public Analyzed analyze(DocumentRequest doc, String content, List<String> flags) {
        try (var scope = capabilities.enter(NAME, doc.documentId())) {
            return analyzeScoped(doc, content, flags);
        }
    }

    private Analyzed analyzeScoped(DocumentRequest doc, String content, List<String> flags) {
        capabilities.checkView("document-set");
        if (content.length() <= CHUNK_CHARS) {
            return grounded(new Analyzed(callAnalysis(doc, content), 0), content, flags);
        }
        List<String> chunks = DocumentChunker.split(content, CHUNK_CHARS);
        if (chunks.size() > MAX_CHUNKS) {
            chunks = chunks.subList(0, MAX_CHUNKS);
            flags.add("DOCUMENT_TRUNCATED_TO_%d_CHUNKS".formatted(MAX_CHUNKS));
        }
        List<ChunkSummary> summaries = mapChunks(chunks);
        StringBuilder condensed = new StringBuilder();
        for (int i = 0; i < summaries.size(); i++) {
            ChunkSummary s = summaries.get(i);
            condensed.append("[Part %d] %s\nFacts: %s\n\n".formatted(i + 1, s.summary(),
                    s.keyFacts() == null ? "" : String.join("; ", s.keyFacts())));
        }
        return grounded(new Analyzed(callAnalysis(doc, condensed.toString()), chunks.size()), content, flags);
    }

    /**
     * WI-08 / F-41 grounding check: extracted key-field values must literally appear in the
     * source document; ungrounded (invented or altered) values flag the document for review.
     */
    private Analyzed grounded(Analyzed analyzed, String source, List<String> flags) {
        var result = io.github.chdurai.agenticbanking.rag.GroundingVerifier.verify(analyzed.analysis().keyFields(), source);
        if (!result.allGrounded()) {
            flags.add("GROUNDING_FAIL: " + String.join(",", result.ungroundedFields()));
            log.warn("[{}] ungrounded key fields stripped from trust: {}", NAME, result.ungroundedFields());
        }
        try {
            String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            return new Analyzed(analyzed.analysis(), analyzed.chunks(), result.sourceSpans(), digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private List<ChunkSummary> mapChunks(List<String> chunks) {
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<ChunkSummary>> futures = new ArrayList<>();
            for (String chunk : chunks) {
                var caller = io.github.chdurai.agenticbanking.security.CallerContext.current();
                futures.add(CompletableFuture.supplyAsync(() -> {
                    try (var ignored = caller.open()) {
                        return llm.call(AgentCallSpec.of(CHUNK_NAME,
                                CHUNK_SYSTEM, Spotlight.wrap(chunk), ChunkSummary.class, 0.0)).value();
                    }
                }, pool));
            }
            return futures.stream().map(CompletableFuture::join).toList();
        }
    }

    private DocumentAnalysis callAnalysis(DocumentRequest doc, String content) {
        String user = "Title: %s\n\nDocument:\n%s".formatted(doc.title(), Spotlight.wrap(content));
        DocumentAnalysis analysis = llm.call(AgentCallSpec.of(NAME, SYSTEM, user, DocumentAnalysis.class, 0.0)).value();
        log.info("[{}] -> {} confidence={}%", NAME, analysis.documentType(),
                analysis.confidencePct());
        return analysis;
    }

    public record Analyzed(DocumentAnalysis analysis, int chunks,
                           java.util.Map<String, io.github.chdurai.agenticbanking.rag.GroundingVerifier.SourceSpan> sourceSpans,
                           String sourceTextSha256) {
        public Analyzed(DocumentAnalysis analysis, int chunks) {
            this(analysis, chunks, java.util.Map.of(), null);
        }
    }
}
