package io.github.chdurai.agenticbanking.documents;

/** OCR abstraction for scanned/image-based documents (e.g., Azure Document Intelligence adapter). */
public interface DocumentOcrPort {
    String extractText(DocumentRequest request);
}
