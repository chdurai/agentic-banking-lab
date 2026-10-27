package io.github.chdurai.agenticbanking.guardrails;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Produces the views of a text that deterministic screens must inspect, so Unicode tricks
 * (full-width forms, zero-width joiners, bidi controls, Cyrillic/Greek look-alikes, l33t,
 * spaced-out letters) cannot hide a payload from a regex.
 */
public final class TextNormalizer {

    /** @param display NFKC, invisibles removed: safe to keep and show downstream.
     *  @param detect lower-case, diacritics stripped, script preserved (for non-Latin lexicons).
     *  @param folded detect + look-alikes mapped to Latin, l33t undone, spaced letters joined. */
    public record Views(String display, String detect, String folded) {}

    private static final Pattern INVISIBLE = Pattern.compile("[\\p{Cf}\\p{Cc}&&[^\\n\\t]]|[\\u2028\\u2029\\u00AD]");
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern SPACES = Pattern.compile("[ \\t\\u00A0]{2,}");
    private static final Pattern SPACED_LETTERS = Pattern.compile("(?<![\\p{L}\\p{N}])(?:\\p{L}[ .\\-_*]){2,}\\p{L}(?![\\p{L}\\p{N}])");

    private static final Map<Character, Character> LOOKALIKES = Map.ofEntries(
            Map.entry('а', 'a'), Map.entry('е', 'e'), Map.entry('о', 'o'), Map.entry('р', 'p'),
            Map.entry('с', 'c'), Map.entry('х', 'x'), Map.entry('у', 'y'), Map.entry('і', 'i'),
            Map.entry('ј', 'j'), Map.entry('ѕ', 's'), Map.entry('һ', 'h'), Map.entry('к', 'k'),
            Map.entry('м', 'm'), Map.entry('т', 't'), Map.entry('в', 'b'), Map.entry('н', 'h'),
            Map.entry('ο', 'o'), Map.entry('α', 'a'), Map.entry('ε', 'e'), Map.entry('ι', 'i'),
            Map.entry('ν', 'v'), Map.entry('ρ', 'p'), Map.entry('τ', 't'), Map.entry('υ', 'u'),
            Map.entry('κ', 'k'), Map.entry('χ', 'x'), Map.entry('ı', 'i'));

    private static final Map<Character, Character> LEET = Map.of(
            '0', 'o', '1', 'i', '3', 'e', '4', 'a', '5', 's', '7', 't', '@', 'a', '$', 's', '!', 'i');

    private TextNormalizer() {}

    public static Views normalize(String input) {
        if (input == null) {
            return new Views(null, "", "");
        }
        String display = Normalizer.normalize(input, Normalizer.Form.NFKC);
        display = INVISIBLE.matcher(display).replaceAll("");
        display = SPACES.matcher(display).replaceAll(" ").strip();

        String detect = MARKS.matcher(Normalizer.normalize(display, Normalizer.Form.NFD)).replaceAll("");
        detect = Normalizer.normalize(detect, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);

        StringBuilder f = new StringBuilder(detect.length());
        for (char c : detect.toCharArray()) {
            char m = LOOKALIKES.getOrDefault(c, c);
            f.append(LEET.getOrDefault(m, m));
        }
        String folded = SPACED_LETTERS.matcher(f).replaceAll(r -> r.group().replaceAll("[ .\\-_*]", ""));
        return new Views(display, detect, folded);
    }

    /** True when most letters are outside Latin, Arabic and Cyrillic, i.e. no lexicon covers them. */
    public static boolean mostlyUncoveredScript(String text) {
        if (text == null) {
            return false;
        }
        int letters = 0;
        int uncovered = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (!Character.isLetter(cp)) {
                continue;
            }
            letters++;
            Character.UnicodeScript s = Character.UnicodeScript.of(cp);
            if (s != Character.UnicodeScript.LATIN && s != Character.UnicodeScript.ARABIC
                    && s != Character.UnicodeScript.CYRILLIC && s != Character.UnicodeScript.COMMON
                    && s != Character.UnicodeScript.INHERITED) {
                uncovered++;
            }
        }
        return letters >= 8 && uncovered * 2 > letters;
    }
}
