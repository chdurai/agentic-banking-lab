package io.github.chdurai.agenticbanking.documents;

import org.springframework.stereotype.Component;

/**
 * Default OCR adapter for local/demo mode: document text is already extracted upstream.
 * Production adapters should call an OCR provider and return extracted plain text.
 */
@Component
public class PassThroughDocumentOcrPort implements DocumentOcrPort {
    @Override
    public String extractText(DocumentRequest request) {
        return request.content();
    }
}
