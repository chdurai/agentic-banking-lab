package io.github.chdurai.agenticbanking.documents;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Local embedded-text extraction only. Scanned/empty pages never masquerade as OCR success. */
@Component
public class PdfTextExtractor {
    public static final int MAX_BYTES = 5 * 1024 * 1024;
    static final int MAX_PAGES = 50;

    public record Extraction(String text, List<String> flags) {
        public Extraction { flags = List.copyOf(flags); }
    }

    public Extraction extract(byte[] bytes) {
        if (bytes.length == 0 || bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException("PDF size is outside the supported range");
        }
        if (bytes.length < 5 || !new String(bytes, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-")) {
            throw new IllegalArgumentException("PDF signature is missing");
        }
        List<String> flags = new ArrayList<>();
        var text = new BoundedText(DocumentService.MAX_CHARS);
        try (var pdf = Loader.loadPDF(bytes)) {
            if (pdf.isEncrypted() || !pdf.getCurrentAccessPermission().canExtractContent()) {
                return new Extraction("", List.of("PDF_RESTRICTED"));
            }
            if (pdf.getNumberOfPages() == 0) return new Extraction("", List.of("PDF_OCR_REQUIRED"));
            if (pdf.getNumberOfPages() > MAX_PAGES) flags.add("PDF_PAGE_LIMIT_REACHED");
            var stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            for (int page = 1; page <= Math.min(pdf.getNumberOfPages(), MAX_PAGES); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                int before = text.value.length();
                stripper.writeText(pdf, text);
                if (text.value.substring(before).isBlank() && !flags.contains("PDF_OCR_REQUIRED")) {
                    flags.add("PDF_OCR_REQUIRED");
                }
            }
        } catch (TextLimitReached e) {
            flags.add("PDF_TEXT_LIMIT_REACHED");
        } catch (InvalidPasswordException e) {
            return new Extraction("", List.of("PDF_RESTRICTED"));
        } catch (IOException e) {
            return new Extraction("", List.of("PDF_EXTRACTION_UNAVAILABLE"));
        }
        return new Extraction(text.value.toString(), flags);
    }

    private static final class TextLimitReached extends IOException {}

    private static final class BoundedText extends Writer {
        private final StringBuilder value = new StringBuilder();
        private final int limit;
        private BoundedText(int limit) { this.limit = limit; }
        @Override public void write(char[] chars, int offset, int length) throws IOException {
            int remaining = limit - value.length();
            value.append(chars, offset, Math.min(remaining, length));
            if (length > remaining) throw new TextLimitReached();
        }
        @Override public void flush() {}
        @Override public void close() {}
    }
}
