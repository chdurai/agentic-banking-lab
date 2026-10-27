package io.github.chdurai.agenticbanking.applicant;

import io.github.chdurai.agenticbanking.security.CallerContext;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

class DemoApplicantDirectoryTest {
    private final DemoApplicantDirectory directory = new DemoApplicantDirectory();

    private DemoApplicantDirectory.Registration registration() {
        return new DemoApplicantDirectory.Registration("Taylor Demo", "1234", new BigDecimal("85000"),
                4, "Demo Works", new BigDecimal("400"));
    }

    @Test void searchesSeededScenariosAndBuildsApplicationFromStoredProfile() {
        assertThat(directory.search("jAnE")).extracting(DemoApplicantDirectory.Profile::applicantId).containsExactly("A001");
        assertThat(directory.search("A002")).hasSize(1);
        var details = new DemoApplicantDirectory.LoanDetails(1, new BigDecimal("25000"), "renovation", 60, true);
        var application = directory.application("A001", details);
        assertThat(application.fullName()).isEqualTo("Jane Cooper");
        assertThat(application.annualIncome()).isEqualByComparingTo("145000");
        assertThat(application.ssnLast4()).isEqualTo("4321");
        assertThat(application.requestedAmount()).isEqualByComparingTo("25000");
        assertThat(application.documents()).contains("id.pdf");
    }

    @Test void onboardedRecordsAreReusableAndDuplicatesAreExplicit() {
        var profile = directory.register(registration());
        assertThat(directory.get(profile.applicantId())).isEqualTo(profile);
        assertThat(profile.verificationStatus()).isEqualTo("DECLARED_NOT_VERIFIED");
        assertThat(profile.documents()).isEmpty();
        assertThat(directory.search("Taylor")).containsExactly(profile);
        assertThatThrownBy(() -> directory.register(registration()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode().value()).isEqualTo(409));
    }

    @Test void tenantRecordsAreIsolatedAndStaleOrMissingProfilesFail() {
        String id;
        try (var ignored = new CallerContext("one", "demo", Set.of("LOAN_UNDERWRITER")).open()) {
            id = directory.register(registration()).applicantId();
        }
        try (var ignored = new CallerContext("two", "demo", Set.of("LOAN_UNDERWRITER")).open()) {
            assertThat(directory.search("Taylor")).isEmpty();
            assertThatThrownBy(() -> directory.get(id))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            error -> assertThat(error.getStatusCode().value()).isEqualTo(404));
        }
        assertThatThrownBy(() -> directory.application("A001",
                new DemoApplicantDirectory.LoanDetails(99, new BigDecimal("25000"), "repair", 60, true)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode().value()).isEqualTo(409));
    }
}
