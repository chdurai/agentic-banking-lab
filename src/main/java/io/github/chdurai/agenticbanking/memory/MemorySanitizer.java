package io.github.chdurai.agenticbanking.memory;

import org.springframework.stereotype.Component;

/**
 * Lightweight sanitizer for episodic memory summaries.
 */
@Component
public class MemorySanitizer {

    public String sanitizeSummary(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String s = text;
        s = s.replaceAll("(?i)\\b[\\w._%+-]+@[\\w.-]+\\.[a-z]{2,}\\b", "<redacted-email>");
        s = s.replaceAll("\\b\\d{3}-\\d{2}-\\d{4}\\b", "<redacted-ssn>");
        s = s.replaceAll("(?<!\\d)(?:\\d[ -]?){13,19}(?!\\d)", "<redacted-card>");
        s = s.replaceAll("\\b(?:\\+?\\d{1,3}[\\s-]?)?(?:\\(?\\d{3}\\)?[\\s-]?)\\d{3}[\\s-]?\\d{4}\\b",
                "<redacted-phone>");
        return s;
    }
}
