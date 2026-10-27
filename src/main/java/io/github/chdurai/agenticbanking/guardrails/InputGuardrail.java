package io.github.chdurai.agenticbanking.guardrails;

import io.github.chdurai.agenticbanking.model.LoanApplication;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Pre-LLM safety layer. Deterministic, fast, no model call:
 *  - Unicode normalisation (NFKC, invisibles, look-alikes, l33t) before every check
 *  - prompt-injection heuristics (multilingual, role-hijack phrases tightened to avoid false positives)
 *  - PII masking (full SSNs, card numbers) so raw PII never reaches model logs
 *  - field length clamping, numeric sanity checks on every structured field
 *  - a "second-line" flag for soft signals that warrant a model-based or human check
 */
@Component
public class InputGuardrail {

    private static final int MAX_FIELD = 500;
    private static final int F = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    private static final List<Pattern> INJECTION_PATTERNS = List.of(
            Pattern.compile("(ignore|disregard|forget|override|bypass).{0,30}(instructions|prompts?|rules|guidelines|polic(y|ies))", F),
            Pattern.compile("disregard (all|any|previous|the above)", F),
            Pattern.compile("you are (now|no longer) (a|an|the|in|dan|free|unrestricted|my)\\b", F),
            Pattern.compile("\\bact as (a|an|the|if you (are|were)|my)?\\s*(system|admin\\w*|root|developer|dan|unrestricted|jailbroken|different|another|evil|underwriter|approver)", F),
            Pattern.compile("new system prompt|jailbreak|developer mode|do anything now|\\bdan mode\\b", F),
            Pattern.compile("\\[system\\]|<\\|im_start\\|>|<<SYS>>|<\\|system\\|>|###\\s*(system|instruction)", F),
            Pattern.compile("(reveal|print|show|repeat|leak) (me )?(your|the) (system )?(prompt|instructions)", F),
            // other languages
            Pattern.compile("(ignore[rz]?|oublie[rz]?|ignora|ignoriere|olvida|ignore)\\s.{0,25}(instructions|instrucciones|anweisungen|consignes|regles|reglas|instrucoes)", F),
            Pattern.compile("tu es maintenant|ahora eres|du bist jetzt|nouvelles? instructions|nuevas instrucciones", F),
            Pattern.compile("(تجاهل|انس).{0,25}(التعليمات|الاوامر|القواعد)", F),
            Pattern.compile("(игнорируй|забудь).{0,25}(инструкци|правила)", F));

    /** Soft signals: not blocked, but flagged so a second-line classifier or a human can look. */
    private static final List<Pattern> SOFT_PATTERNS = List.of(
            Pattern.compile("(approve|mark|set|change|override).{0,25}(decision|loan|application|status|score).{0,25}(approved?|declined?|low risk|pass)", F),
            Pattern.compile("\\b(system|assistant|developer|tool) (message|prompt|role|instruction)", F),
            Pattern.compile("[A-Za-z0-9+/=]{60,}"),
            Pattern.compile("(?i)\\bbase64\\b|\\bdecode (this|the following)\\b"));

    private static final Pattern FULL_SSN = Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b");
    private static final Pattern CARD_NUMBER = Pattern.compile("\\b(?:\\d[ -]?){13,19}\\b");
    private static final BigDecimal MAX_MONEY = new BigDecimal("1000000000");

    public GuardrailResult screen(LoanApplication app) {
        List<String> flags = new ArrayList<>();
        boolean[] soft = {false};

        String applicantId = clean(app.applicantId(), "applicantId", flags, 64, soft);
        String name = clean(app.fullName(), "fullName", flags, MAX_FIELD, soft);
        String purpose = clean(app.loanPurpose(), "loanPurpose", flags, MAX_FIELD, soft);
        String employer = clean(app.employer(), "employer", flags, MAX_FIELD, soft);
        List<String> docs = app.documents() == null ? List.of()
                : app.documents().stream().map(d -> clean(d, "documents", flags, MAX_FIELD, soft)).toList();

        boolean numericProblem = screenNumbers(app, flags);
        String ssn = app.ssnLast4();
        if (ssn == null || !ssn.matches("\\d{4}")) {
            flags.add("invalid ssnLast4 (must be exactly 4 digits)");
            ssn = "0000";
            numericProblem = true;
        }

        boolean injection = flags.stream().anyMatch(f -> f.contains("injection")) || numericProblem;
        if (soft[0]) {
            flags.add("second-line review recommended");
        }

        LoanApplication sanitized = new LoanApplication(
                applicantId, name, ssn, app.annualIncome(), app.employmentYears(),
                app.requestedAmount(), purpose, app.termMonths(), app.existingMonthlyDebt(),
                employer, docs);

        return new GuardrailResult(sanitized, List.copyOf(flags), injection, soft[0]);
    }

    private boolean screenNumbers(LoanApplication app, List<String> flags) {
        boolean bad = false;
        for (var e : java.util.Map.of("annualIncome", nz(app.annualIncome()),
                "requestedAmount", nz(app.requestedAmount()),
                "existingMonthlyDebt", nz(app.existingMonthlyDebt())).entrySet()) {
            BigDecimal v = e.getValue();
            if (v.signum() < 0 || v.compareTo(MAX_MONEY) > 0) {
                flags.add("numeric value out of range: " + e.getKey());
                bad = true;
            }
        }
        if (app.employmentYears() < 0 || app.employmentYears() > 70 || app.termMonths() < 1 || app.termMonths() > 480) {
            flags.add("numeric value out of range: employmentYears/termMonths");
            bad = true;
        }
        return bad;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private String clean(String value, String field, List<String> flags, int maxLength, boolean[] soft) {
        if (value == null) {
            return null;
        }
        TextNormalizer.Views views = TextNormalizer.normalize(value);
        String v = views.display();
        if (matchesAny(INJECTION_PATTERNS, views)) {
            flags.add("prompt-injection suspected in field: " + field);
        } else if (matchesAny(SOFT_PATTERNS, views)) {
            soft[0] = true;
        }
        if (FULL_SSN.matcher(v).find()) {
            v = FULL_SSN.matcher(v).replaceAll("***-**-****");
            flags.add("full SSN masked in field: " + field);
        }
        if (CARD_NUMBER.matcher(v).find()) {
            v = CARD_NUMBER.matcher(v).replaceAll("[card-redacted]");
            flags.add("card number masked in field: " + field);
        }
        if (v.length() > maxLength) {
            v = v.substring(0, maxLength);
            flags.add("overlong field truncated: " + field);
        }
        return v;
    }

    private static boolean matchesAny(List<Pattern> patterns, TextNormalizer.Views views) {
        for (Pattern p : patterns) {
            if (p.matcher(views.display()).find() || p.matcher(views.detect()).find()
                    || p.matcher(views.folded()).find()) {
                return true;
            }
        }
        return false;
    }

    /** Screen arbitrary free text (non-loan flows like incident reports). */
    public TextScreening screenText(String fieldName, String value) {
        return screenText(fieldName, value, MAX_FIELD);
    }

    /** Same screening with a larger length cap, for long-form text (grievances, documents). */
    public TextScreening screenText(String fieldName, String value, int maxLength) {
        List<String> flags = new ArrayList<>();
        boolean[] soft = {false};
        String cleaned = clean(value, fieldName, flags, maxLength, soft);
        boolean blocked = flags.stream().anyMatch(f -> f.contains("prompt-injection"));
        if (soft[0]) {
            flags.add("second-line review recommended");
        }
        return new TextScreening(cleaned, List.copyOf(flags), blocked, soft[0]);
    }

    public record TextScreening(String sanitized, List<String> flags, boolean blocked, boolean secondLine) {}
}
