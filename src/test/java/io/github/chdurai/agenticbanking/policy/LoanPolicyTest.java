package io.github.chdurai.agenticbanking.policy;

import io.github.chdurai.agenticbanking.model.AgentReport;
import io.github.chdurai.agenticbanking.model.AgentReport.Status;
import io.github.chdurai.agenticbanking.model.LoanApplication;
import io.github.chdurai.agenticbanking.model.LoanDecision;
import io.github.chdurai.agenticbanking.model.LoanDecision.Decision;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class LoanPolicyTest {

    private final LoanPolicyProperties props = LoanPolicyProperties.defaults();
    private final LoanPolicyEngine engine = new LoanPolicyEngine(props);
    private final InvariantChecker checker = new InvariantChecker(props);
    private final PolicyMerge merge = new PolicyMerge();

    private static LoanApplication app(String amount, int term) {
        return new LoanApplication("APP-1", "Jane", "1234", new BigDecimal("90000"), 5,
                new BigDecimal(amount), "auto", term, BigDecimal.ZERO, "Acme", List.of());
    }

    private static AgentReport r(String agent, Status s, int risk, String... flags) {
        return new AgentReport(agent, s, risk, "s", List.of(), List.of(flags));
    }

    private static LoanDecision draft(Decision d, String amount, String apr, List<String> adverse, List<String> cond,
                                      String reasoning) {
        return new LoanDecision("APP-1", d, amount == null ? null : new BigDecimal(amount),
                apr == null ? null : new BigDecimal(apr), cond, adverse, reasoning, List.of(), 0, Instant.now());
    }

    private PolicyEnvelope env(AgentReport... reports) {
        return engine.evaluate(app("35000", 60), List.of(reports));
    }

    @Test
    void cleanFileApprovesWithPolicyPricing() {
        PolicyEnvelope e = env(r("kyc", Status.PASS, 10), r("credit-risk", Status.PASS, 20));
        assertThat(e.floor()).isEqualTo(Decision.APPROVED);
        assertThat(e.apr()).isEqualByComparingTo("8.00");
        assertThat(e.policyVersion()).isEqualTo(props.version());
    }

    @Test
    void riskScoreBoundary59And60() {
        assertThat(env(r("credit-risk", Status.PASS, 59)).floor()).isEqualTo(Decision.APPROVED);
        assertThat(env(r("credit-risk", Status.PASS, 60)).floor()).isEqualTo(Decision.MANUAL_REVIEW);
    }

    @Test
    void warnCountBoundaryOneVsTwo() {
        assertThat(env(r("a", Status.WARN, 10)).floor()).isEqualTo(Decision.APPROVED);
        assertThat(env(r("a", Status.WARN, 10), r("b", Status.WARN, 10)).floor()).isEqualTo(Decision.MANUAL_REVIEW);
    }

    @Test
    void failDeclinesUnlessPureDataGapOrSanctions() {
        assertThat(env(r("credit-risk", Status.FAIL, 90)).floor()).isEqualTo(Decision.DECLINED);
        assertThat(env(r("intake", Status.FAIL, 50)).floor()).isEqualTo(Decision.MANUAL_REVIEW);
        PolicyEnvelope hold = env(r("kyc", Status.FAIL, 95), r("credit-risk", Status.FAIL, 90));
        assertThat(hold.floor()).isEqualTo(Decision.COMPLIANCE_HOLD);
        assertThat(hold.customerReasonCodes()).isEmpty();
        assertThat(env(r("compliance", Status.FAIL, 90, "Possible sanctions exposure")).floor())
                .isEqualTo(Decision.COMPLIANCE_HOLD);
    }

    @Test
    void emptyReportsNeverApprove() {
        assertThat(env().floor()).isEqualTo(Decision.MANUAL_REVIEW);
    }

    @Test
    void capsAmountTermAndApr() {
        PolicyEnvelope big = engine.evaluate(app("250000", 60), List.of(r("kyc", Status.PASS, 10)));
        assertThat(big.maxAmount()).isEqualByComparingTo("100000");
        assertThat(engine.evaluate(app("5000", 96), List.of(r("kyc", Status.PASS, 10))).floor())
                .isEqualTo(Decision.DECLINED);
        assertThat(engine.apr(List.of(r("a", Status.PASS, 100000)))).isEqualByComparingTo("36.00");
    }

    @Test
    void floorIsMonotonicOverRandomInputs() {
        Random rnd = new Random(42);
        String[] agents = {"kyc", "intake", "credit-risk", "compliance"};
        for (int i = 0; i < 500; i++) {
            List<AgentReport> base = new java.util.ArrayList<>();
            for (String a : agents) {
                base.add(r(a, Status.values()[rnd.nextInt(3)], rnd.nextInt(101)));
            }
            Decision floor = engine.evaluate(app("20000", 48), base).floor();
            if (base.stream().anyMatch(x -> x.agentName().equals("kyc") && x.failed())) {
                assertThat(floor).isEqualTo(Decision.COMPLIANCE_HOLD);
            } else if (base.stream().anyMatch(x -> x.failed() && !x.agentName().equals("intake"))) {
                assertThat(floor.strictness()).isGreaterThanOrEqualTo(Decision.DECLINED.strictness());
            } else if (base.stream().anyMatch(AgentReport::failed)) {
                assertThat(floor.strictness()).isGreaterThanOrEqualTo(Decision.MANUAL_REVIEW.strictness());
            }
        }
    }

    @Test
    void approvedDespiteFailIsBlocking() {
        PolicyEnvelope e = env(r("credit-risk", Status.FAIL, 90));
        var v = checker.check(draft(Decision.APPROVED, "35000", "8.0", List.of(), List.of(), "ok"), e);
        assertThat(InvariantChecker.hasBlocking(v)).isTrue();
        assertThat(v).extracting(InvariantChecker.Violation::code).contains("DECISION_WEAKER_THAN_POLICY");
    }

    @Test
    void prohibitedBasisDetectedWithoutFalsePositives() {
        PolicyEnvelope e = env(r("kyc", Status.PASS, 10));
        var bad = checker.check(draft(Decision.APPROVED, "35000", "7.5", List.of(), List.of(),
                "Applicant's age and marital status are favourable"), e);
        assertThat(bad).extracting(InvariantChecker.Violation::code).contains("PROHIBITED_BASIS");
        var ok = checker.check(draft(Decision.APPROVED, "35000", "7.5", List.of(), List.of(),
                "Average risk is low; usage and message history are fine, stage passed"), e);
        assertThat(ok).extracting(InvariantChecker.Violation::code).doesNotContain("PROHIBITED_BASIS");
    }

    @Test
    void correctableViolationsAreRepairedByMerge() {
        PolicyEnvelope e = env(r("kyc", Status.PASS, 10), r("credit-risk", Status.PASS, 20));
        LoanDecision d = draft(Decision.APPROVED, "500000", "3.0", List.of(), List.of(), "fine");
        var v = checker.check(d, e);
        assertThat(InvariantChecker.hasBlocking(v)).isFalse();
        LoanDecision out = merge.merge(d, e, v, false, "APP-1", List.of());
        assertThat(out.approvedAmount()).isEqualByComparingTo("35000");
        assertThat(out.interestRateApr()).isEqualByComparingTo("8.00");
        assertThat(out.internalReasonCodes()).contains("APR_MISMATCH", "AMOUNT_ABOVE_CAP");
    }

    @Test
    void unresolvedFloorViolationForcesPolicyDecision() {
        PolicyEnvelope e = env(r("credit-risk", Status.FAIL, 90));
        LoanDecision d = draft(Decision.APPROVED, "35000", "8.0", List.of(), List.of(), "fine");
        LoanDecision out = merge.merge(d, e, checker.check(d, e), false, "APP-1", List.of());
        assertThat(out.decision()).isEqualTo(Decision.DECLINED);
        assertThat(out.adverseActionReasons()).isNotEmpty();
        assertThat(out.approvedAmount()).isEqualByComparingTo("0");
        assertThat(out.customerReasonCodes()).isNotEmpty();
    }

    @Test
    void stuckCriticOrProhibitedBasisRoutesToHuman() {
        PolicyEnvelope e = env(r("kyc", Status.PASS, 10));
        LoanDecision d = draft(Decision.APPROVED, "35000", "7.5", List.of(), List.of(), "fine");
        LoanDecision stuck = merge.merge(d, e, List.of(), true, "APP-1", List.of());
        assertThat(stuck.decision()).isEqualTo(Decision.MANUAL_REVIEW);
        assertThat(stuck.internalReasonCodes()).contains("CRITIC_UNRESOLVED");
        assertThat(stuck.conditions()).isNotEmpty();

        LoanDecision bias = draft(Decision.APPROVED, "35000", "7.5", List.of(), List.of(), "because of her religion");
        LoanDecision out = merge.merge(bias, e, checker.check(bias, e), false, "APP-1", List.of());
        assertThat(out.decision()).isEqualTo(Decision.MANUAL_REVIEW);
        assertThat(out.reasoning()).doesNotContain("religion");
    }

    @Test
    void complianceHoldCustomerViewLeaksNothing() {
        PolicyEnvelope e = env(r("kyc", Status.FAIL, 95, "OFAC sanctions match"));
        LoanDecision internal = merge.hold(e, "APP-1", List.of(r("kyc", Status.FAIL, 95, "OFAC sanctions match")));
        assertThat(internal.internalReasonCodes()).contains("SANCTIONS_MATCH");
        LoanDecision view = internal.customerView();
        assertThat(view.agentReports()).isEmpty();
        assertThat(view.internalReasonCodes()).isEmpty();
        assertThat(view.adverseActionReasons()).isEmpty();
        assertThat(view.reasoning()).doesNotContainIgnoringCase("sanction");
    }
}
