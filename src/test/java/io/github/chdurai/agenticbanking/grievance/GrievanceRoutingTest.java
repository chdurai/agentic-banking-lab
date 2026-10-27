package io.github.chdurai.agenticbanking.grievance;

import io.github.chdurai.agenticbanking.grievance.GrievanceResolution.Route;
import io.github.chdurai.agenticbanking.grievance.GrievanceTriage.Severity;
import io.github.chdurai.agenticbanking.grievance.GrievanceTriage.Theme;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GrievanceRoutingTest {

    private static GrievanceTriage t(Severity sev, Theme theme, boolean retaliation, boolean vulnerable, int conf) {
        return new GrievanceTriage(List.of(theme), sev, GrievanceTriage.Sentiment.CONCERNED, retaliation,
                vulnerable, conf, "s", "r");
    }

    @Test
    void corruptionGoesToConfidentialTeam() {
        var d = GrievanceRouting.decide(t(Severity.LOW, Theme.CORRUPTION_OR_FRAUD, false, false, 95));
        assertThat(d.route()).isEqualTo(Route.CONFIDENTIAL_CASE_TEAM);
        assertThat(d.humanReviewRequired()).isTrue();
    }

    @Test
    void criticalOrRetaliationGets24hSafeguardsReview() {
        var critical = GrievanceRouting.decide(t(Severity.CRITICAL, Theme.LABOR_AND_WORKING_CONDITIONS, false, false, 95));
        var retaliation = GrievanceRouting.decide(t(Severity.LOW, Theme.STAKEHOLDER_ENGAGEMENT, true, false, 95));
        assertThat(critical.slaHours()).isEqualTo(24);
        assertThat(retaliation.slaHours()).isEqualTo(24);
        assertThat(retaliation.route()).isEqualTo(Route.SAFEGUARDS_SPECIALIST);
    }

    @Test
    void highSeverityVulnerableOrLowConfidenceNeedHumanReview() {
        assertThat(GrievanceRouting.decide(t(Severity.HIGH, Theme.OTHER, false, false, 90)).humanReviewRequired()).isTrue();
        assertThat(GrievanceRouting.decide(t(Severity.LOW, Theme.OTHER, false, true, 90)).humanReviewRequired()).isTrue();
        assertThat(GrievanceRouting.decide(t(Severity.LOW, Theme.OTHER, false, false, 59)).humanReviewRequired()).isTrue();
    }

    @Test
    void moderateAndLowAreRoutedToGrmOfficerWithoutReview() {
        var moderate = GrievanceRouting.decide(t(Severity.MODERATE, Theme.OTHER, false, false, 80));
        var low = GrievanceRouting.decide(t(Severity.LOW, Theme.OTHER, false, false, 80));
        assertThat(moderate.route()).isEqualTo(Route.PROJECT_GRM_OFFICER);
        assertThat(moderate.humanReviewRequired()).isFalse();
        assertThat(moderate.slaHours()).isLessThan(low.slaHours());
    }

    @Test
    void missingSeverityFailsSafeToHigh() {
        var d = GrievanceRouting.decide(new GrievanceTriage(List.of(Theme.OTHER), null, null, false, false, 90, "s", "r"));
        assertThat(d.humanReviewRequired()).isTrue();
    }

    @Test
    void sensitiveDetectorCatchesSeaShAndThreatsButNotOrdinaryComplaints() {
        assertThat(SensitiveCaseDetector.isSensitive("The foreman demanded sex for work")).isTrue();
        assertThat(SensitiveCaseDetector.isSensitive("He sexually harassed two women at the camp")).isTrue();
        assertThat(SensitiveCaseDetector.isSensitive("They threatened my family if I complain")).isTrue();
        assertThat(SensitiveCaseDetector.isSensitive("Dust from trucks covers our houses")).isFalse();
        assertThat(SensitiveCaseDetector.isSensitive("Wages unpaid for six weeks", null)).isFalse();
    }
}
