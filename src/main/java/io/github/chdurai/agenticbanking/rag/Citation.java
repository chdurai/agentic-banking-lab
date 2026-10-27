package io.github.chdurai.agenticbanking.rag;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Uniform citation reference (WI-08): {@code POL-CRD-001 §3 v2}. The section and version
 * are optional in model output; verification treats an absent section/version as "any
 * section/version of that document" and an unknown document as unverifiable.
 */
public record Citation(String docId, Integer section, String version) {

    private static final Pattern PATTERN = Pattern.compile(
            "\\b([A-Z][A-Z0-9]*(?:-[A-Z0-9]+)*-\\d+)(?: §(\\d+))?(?: (v\\d+))?\\b");

    /** Parses the first citation-looking reference in the text. */
    public static Optional<Citation> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher m = PATTERN.matcher(text);
        if (!m.find()) {
            return Optional.empty();
        }
        return Optional.of(new Citation(m.group(1),
                m.group(2) == null ? null : Integer.valueOf(m.group(2)), m.group(3)));
    }

    /**
     * True when this citation refers to {@code other}: same document, and same
     * section/version whenever this citation pins them.
     */
    public boolean satisfiedBy(Citation retrieved) {
        return docId.equals(retrieved.docId)
                && (section == null || section.equals(retrieved.section))
                && (version == null || version.equals(retrieved.version));
    }

    public String format() {
        return docId + (section == null ? "" : " §" + section) + (version == null ? "" : " " + version);
    }
}
