package io.github.chdurai.agenticbanking.guardrails;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Deterministic, high-recall circuit-breaker lexicon for the most sensitive grievances
 * (sexual exploitation/abuse/harassment, violence threats, self-harm) in EN, FR, ES, PT, AR, RU, SW.
 * Runs on every normalised view of the text, so obfuscation does not evade it. A false positive costs
 * a human a look; a false negative exposes a survivor's account to an automated pipeline.
 */
public final class MultilingualSensitiveDetector {

    private static final int F = Pattern.UNICODE_CASE;

    private static final List<Pattern> LEXICON = List.of(
            // English
            p("\\b(sexual(ly)?|sex)\\b.{0,40}\\b(abus\\w*|assault\\w*|harass\\w*|exploit\\w*|favou?rs?|violence|coerc\\w*)\\b"),
            p("\\b(abus\\w*|assault\\w*|harass\\w*|exploit\\w*)\\b.{0,40}\\b(sexual(ly)?|sex)\\b"),
            p("\\b(rape[ds]?|raping|rapist\\w*|molest\\w*|groped?|trafficking|trafficked|sex(ual)? slave\\w*)\\b"),
            p("\\bsex (in )?(exchange|for) (for )?(work|job|pay|wages|food|money)"),
            p("\\b(threat(en\\w*)?|kill\\w*|murder\\w*|beat(en|ing)?|shot)\\b.{0,40}\\b(me|us|my family|my children|whistleblower|complain\\w*)\\b"),
            p("\\b(self[- ]?harm|suicid\\w*|kill myself|end my life)\\b"),
            // French (diacritics already stripped)
            p("\\b(viol|violee?s?|violer|violent(e|ee)?s? sexuellement|agressions? sexuelles?|harcelement sexuel|abus sexuels?|attouchements?|exploitation sexuelle|violences? sexuelles?)\\b"),
            p("\\b(traite (des )?(etres humains|personnes)|esclave sexuel\\w*|sexe contre|menaces? de mort|me tuer|me faire du mal|suicid\\w*)\\b"),
            // Spanish / Portuguese
            p("\\b(violacion|violaciones|violar|violaron|violo|abuso sexual|acoso sexual|agresion sexual|tocamientos|explotacion sexual|esclavitud sexual|trata de personas)\\b"),
            p("\\b(amenaza\\w* de muerte|me van a matar|hacerme dano|sexo a cambio|suicid\\w*)\\b"),
            p("\\b(estupro|assedio sexual|abuso sexual|exploracao sexual|trafico de pessoas|ameaca\\w* de morte)\\b"),
            // Swahili
            p("\\b(ubakaji|kubaka|alinibaka|unyanyasaji wa kijinsia|unyonyaji wa kijinsia|kujiua)\\b"),
            // Arabic (no word boundaries; scripts are unspaced in places)
            p("(اغتصاب|تحرش|اعتداء جنسي|استغلال جنسي|عنف جنسي|الاتجار بالبشر|انتحار|تهديد بالقتل|سيقتلون|قتل)"),
            // Russian
            p("(изнасил|домогательств|сексуальн\\S{0,10}\\s+(насили|эксплуат|принужд)|самоубийств|угрожа\\S{0,10}\\s+(убить|смерть)|торговл\\S{0,5}\\s+людьми)")
    );

    private MultilingualSensitiveDetector() {}

    private static Pattern p(String regex) {
        return Pattern.compile(regex, F);
    }

    public static boolean isSensitive(String... texts) {
        for (String text : texts) {
            if (text == null || text.isBlank()) {
                continue;
            }
            var v = TextNormalizer.normalize(text);
            for (String view : new String[]{v.detect(), v.folded()}) {
                for (Pattern pat : LEXICON) {
                    if (pat.matcher(view).find()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
