package io.github.chdurai.agenticbanking.rag;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic citation verifier (WI-08, design 7.6): every cited id must be in the
 * retrieved set. Unknown citations are stripped (and callers flag the report); a model
 * can never ground a conclusion in a document that was never retrieved.
 */
public final class CitationVerifier {

    /** Marker substituted where an unverifiable citation was removed. */
    public static final String REMOVED = "[unverified citation removed]";

    private static final Pattern CITATION_IN_TEXT = Pattern.compile(
            "\\b([A-Z][A-Z0-9]*(?:-[A-Z0-9]+)*-\\d+)(?: §(\\d+))?(?: (v\\d+))?\\b");

    public record Result(List<String> verified, List<String> stripped) {
        public boolean allValid() {
            return stripped.isEmpty();
        }
    }

    private CitationVerifier() {
    }

    /** Splits claimed citations into those satisfied by the retrieved chunks and those stripped. */
    public static Result verify(Collection<String> cited, List<String> retrievedChunks) {
        List<Citation> retrieved = retrievedChunks.stream()
                .map(Citation::parse)
                .flatMap(java.util.Optional::stream)
                .toList();
        List<String> verified = new ArrayList<>();
        List<String> stripped = new ArrayList<>();
        for (String c : cited == null ? List.<String>of() : cited) {
            boolean ok = Citation.parse(c)
                    .map(claim -> retrieved.stream().anyMatch(claim::satisfiedBy))
                    .orElse(false);
            (ok ? verified : stripped).add(c);
        }
        return new Result(List.copyOf(verified), List.copyOf(stripped));
    }

    /** Replaces every unverifiable citation reference in free text with {@link #REMOVED}. */
    public static String scrub(String text, List<String> retrievedChunks) {
        if (text == null || text.isBlank()) {
            return text;
        }
        List<Citation> retrieved = retrievedChunks.stream()
                .map(Citation::parse)
                .flatMap(java.util.Optional::stream)
                .toList();
        Matcher m = CITATION_IN_TEXT.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            Citation claim = new Citation(m.group(1),
                    m.group(2) == null ? null : Integer.valueOf(m.group(2)), m.group(3));
            boolean ok = retrieved.stream().anyMatch(claim::satisfiedBy);
            m.appendReplacement(out, Matcher.quoteReplacement(ok ? m.group() : REMOVED));
        }
        m.appendTail(out);
        return out.toString();
    }
}
