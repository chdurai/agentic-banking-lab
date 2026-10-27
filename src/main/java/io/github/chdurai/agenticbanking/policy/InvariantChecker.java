package io.github.chdurai.agenticbanking.policy;

import io.github.chdurai.agenticbanking.model.LoanDecision;
import io.github.chdurai.agenticbanking.model.LoanDecision.Decision;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Checks an LLM-drafted decision against the policy envelope. BLOCKING violations send the
 * draft back for revision (and are enforced after the revision budget is spent); CORRECTABLE
 * ones are repaired deterministically during merge.
 */
@Component
public class InvariantChecker {

    public enum Kind { BLOCKING, CORRECTABLE }

    public record Violation(Kind kind, String code, String message) {}

    private final LoanPolicyProperties props;
    private final Pattern prohibited;

    public InvariantChecker(LoanPolicyProperties props) {
        this.props = props;
        List<String> terms = new ArrayList<>(props.prohibitedTerms());
        terms.add("sex");
        terms.add("age");
        this.prohibited = Pattern.compile("\\b(" + String.join("|",
                terms.stream().map(t -> Pattern.quote(t.toLowerCase(Locale.ROOT))).toList()) + ")\\b");
    }

    public List<Violation> check(LoanDecision draft, PolicyEnvelope env) {
        List<Violation> out = new ArrayList<>();
        if (draft.decision().strictness() < env.floor().strictness()) {
            out.add(new Violation(Kind.BLOCKING, "DECISION_WEAKER_THAN_POLICY",
                    "Policy requires at least %s (%s) but the draft says %s"
                            .formatted(env.floor(), String.join(", ", env.internalReasonCodes()), draft.decision())));
        }
        String hit = prohibitedBasis(draft);
        if (hit != null) {
            out.add(new Violation(Kind.BLOCKING, "PROHIBITED_BASIS",
                    "Decision text references prohibited basis '" + hit + "' (ECOA/Reg B)"));
        }
        if (draft.decision() == Decision.APPROVED) {
            if (draft.interestRateApr() == null
                    || draft.interestRateApr().subtract(env.apr()).abs().compareTo(props.aprTolerance()) > 0) {
                out.add(new Violation(Kind.CORRECTABLE, "APR_MISMATCH",
                        "APR must be " + env.apr() + " under " + env.policyVersion()));
            }
            if (draft.approvedAmount() == null || draft.approvedAmount().compareTo(env.maxAmount()) > 0) {
                out.add(new Violation(Kind.CORRECTABLE, "AMOUNT_ABOVE_CAP",
                        "Approved amount may not exceed " + env.maxAmount()));
            }
        }
        if (draft.decision() == Decision.DECLINED && isEmpty(draft.adverseActionReasons())) {
            out.add(new Violation(Kind.CORRECTABLE, "MISSING_ADVERSE_REASONS",
                    "A decline requires specific adverse-action reasons"));
        }
        if (draft.decision() == Decision.MANUAL_REVIEW && isEmpty(draft.conditions())) {
            out.add(new Violation(Kind.CORRECTABLE, "MISSING_REVIEW_CONDITIONS",
                    "A manual review must list what the underwriter verifies"));
        }
        return out;
    }

    public static boolean hasBlocking(List<Violation> violations) {
        return violations.stream().anyMatch(v -> v.kind() == Kind.BLOCKING);
    }

    String prohibitedBasis(LoanDecision d) {
        List<String> texts = new ArrayList<>();
        texts.add(d.reasoning());
        if (d.adverseActionReasons() != null) {
            texts.addAll(d.adverseActionReasons());
        }
        if (d.conditions() != null) {
            texts.addAll(d.conditions());
        }
        for (String t : texts) {
            if (t == null) {
                continue;
            }
            var m = prohibited.matcher(t.toLowerCase(Locale.ROOT));
            if (m.find()) {
                return m.group(1);
            }
        }
        return null;
    }

    private static boolean isEmpty(List<String> l) {
        return l == null || l.stream().noneMatch(s -> s != null && !s.isBlank());
    }
}
