package io.github.chdurai.agenticbanking.rag;

import java.util.List;
import java.util.Map;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Deterministic grounding verifier (WI-08 / F-41): every extracted key-field value must
 * literally appear in the source document. Catches altered-value extraction (a model
 * inventing or modifying amounts, dates, parties) before the result is trusted.
 */
public final class GroundingVerifier {

    /** UTF-16 offsets into the screened extracted text, not the original file or a verification of its issuer. */
    public record SourceSpan(int startInclusive, int endExclusive) {
        public SourceSpan {
            if (startInclusive < 0 || endExclusive <= startInclusive) {
                throw new IllegalArgumentException("Invalid document source span");
            }
        }
    }

    public record Result(List<String> groundedFields, List<String> ungroundedFields,
                         Map<String, SourceSpan> sourceSpans) {
        public boolean allGrounded() {
            return ungroundedFields.isEmpty();
        }
    }

    private GroundingVerifier() {
    }

    private static final Pattern TOKEN = Pattern.compile(
            "[-+]?[\\p{L}\\p{M}\\p{N}]+(?:[.,]\\p{N}+)*|\\p{Sc}");
    private static final Pattern NUMBER = Pattern.compile(
            "[-+]?(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)(?:\\.[0-9]+)?");
    private record Token(String value, int start, int end) {}

    public static Result verify(Map<String, String> keyFields, String source) {
        List<Token> sourceTokens = tokens(source);
        List<String> grounded = new ArrayList<>();
        List<String> ungrounded = new ArrayList<>();
        Map<String, SourceSpan> spans = new LinkedHashMap<>();
        if (keyFields != null) {
            keyFields.forEach((field, value) -> {
                List<Token> valueTokens = tokens(value);
                SourceSpan span = find(sourceTokens, valueTokens);
                boolean present = span != null;
                (present ? grounded : ungrounded).add(field);
                if (present) spans.put(field, span);
            });
        }
        return new Result(List.copyOf(grounded), List.copyOf(ungrounded), Map.copyOf(spans));
    }

    private static SourceSpan find(List<Token> source, List<Token> value) {
        if (value.isEmpty()) return null;
        for (int start = 0; start <= source.size() - value.size(); start++) {
            boolean matches = true;
            for (int offset = 0; offset < value.size(); offset++) {
                if (!source.get(start + offset).value().equals(value.get(offset).value())) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return new SourceSpan(source.get(start).start(), source.get(start + value.size() - 1).end());
            }
        }
        return null;
    }

    private static List<Token> tokens(String text) {
        if (text == null) return List.of();
        List<Token> tokens = new ArrayList<>();
        var matcher = TOKEN.matcher(text);
        while (matcher.find()) {
            String token = matcher.group();
            String normalized = NUMBER.matcher(token).matches()
                    ? new BigDecimal(token.replace(",", "")).stripTrailingZeros().toPlainString()
                    : token.toLowerCase(Locale.ROOT);
            tokens.add(new Token(normalized, matcher.start(), matcher.end()));
        }
        return tokens;
    }
}
