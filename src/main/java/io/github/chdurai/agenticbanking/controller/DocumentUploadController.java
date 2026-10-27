package io.github.chdurai.agenticbanking.controller;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import io.github.chdurai.agenticbanking.cases.CaseService;
import io.github.chdurai.agenticbanking.documents.DocumentResult;
import io.github.chdurai.agenticbanking.documents.DocumentService;
import io.github.chdurai.agenticbanking.documents.PdfTextExtractor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@RestController
@ConditionalOnProperty(prefix = "platform.documents.pdf", name = "enabled", havingValue = "true")
public class DocumentUploadController {
    private final DocumentService documents;
    private final CaseService cases;

    public DocumentUploadController(DocumentService documents, CaseService cases) {
        this.documents = documents;
        this.cases = cases;
    }

    @PostMapping(value = "/api/documents/analyze-file", consumes = "multipart/form-data")
    public ResponseEntity<DocumentResult> analyzeFile(
            @RequestParam @NotBlank @Size(max = 64) String documentId,
            @RequestParam(required = false) @Size(max = 500) String title,
            @RequestParam MultipartFile file,
            @RequestHeader("Idempotency-Key") String key) throws IOException {
        if (key.isBlank() || key.length() > 200 || key.chars().anyMatch(Character::isISOControl)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Idempotency-Key");
        }
        if (!"application/pdf".equalsIgnoreCase(file.getContentType())) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Only application/pdf is supported");
        }
        if (file.getSize() > PdfTextExtractor.MAX_BYTES) throw oversized();
        byte[] bytes;
        try (var input = file.getInputStream()) {
            bytes = input.readNBytes(PdfTextExtractor.MAX_BYTES + 1);
        }
        if (bytes.length > PdfTextExtractor.MAX_BYTES) throw oversized();
        String digest;
        try { digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 is unavailable", e); }
        var metadata = new PdfSubmission(documentId, title, digest);
        var outcome = cases.runIdempotent("document", key, documentId, metadata, DocumentResult.class,
                "document.case.completed", () -> documents.analyzePdf(documentId, title, bytes));
        return ResponseEntity.ok().header("X-Case-Id", outcome.caseId())
                .header("Idempotent-Replayed", Boolean.toString(outcome.replayed())).body(outcome.result());
    }

    private static ResponseStatusException oversized() {
        return new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "PDF exceeds the 5 MiB limit");
    }

    private record PdfSubmission(String documentId, String title, String sha256) {}
}
