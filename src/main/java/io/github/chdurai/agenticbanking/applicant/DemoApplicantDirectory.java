package io.github.chdurai.agenticbanking.applicant;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.*;
import io.github.chdurai.agenticbanking.model.LoanApplication;
import io.github.chdurai.agenticbanking.security.CallerContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Synthetic customer master for the local showcase; like default case storage, resets on restart. */
@Service
public class DemoApplicantDirectory {
    public record Registration(
            @NotBlank @Size(max = 120) String fullName,
            @NotBlank @Pattern(regexp = "[0-9]{4}") String ssnLast4,
            @NotNull @DecimalMin("0") BigDecimal annualIncome,
            @Min(0) @Max(60) int employmentYears,
            @NotBlank @Size(max = 120) String employer,
            @NotNull @DecimalMin("0") BigDecimal existingMonthlyDebt) {}

    public record Profile(String applicantId, String fullName, @JsonIgnore String ssnLast4,
                          BigDecimal annualIncome, int employmentYears, String employer,
                          BigDecimal existingMonthlyDebt, List<String> documents,
                          String source, String verificationStatus, Instant recordedAt, long version) {}

    public record LoanDetails(@Min(1) long profileVersion,
                              @NotNull @DecimalMin("1000") BigDecimal requestedAmount,
                              @NotBlank @Size(max = 200) String loanPurpose,
                              @Min(6) @Max(360) int termMonths,
                              @AssertTrue boolean confirmed) {}

    private final Map<String, Map<String, Profile>> tenants = new ConcurrentHashMap<>();

    private Map<String, Profile> profiles() {
        return tenants.computeIfAbsent(CallerContext.current().tenantId(), ignored -> {
            Map<String, Profile> profiles = new ConcurrentHashMap<>();
            seed(profiles, "A001", "Jane Cooper", "4321", "145000", 8, "Acme Corp", "1200");
            seed(profiles, "A002", "Alex Morgan", "5678", "62000", 3, "Northwind Supplies", "1800");
            seed(profiles, "A003", "Sam Rivera", "9012", "98000", 6, "Contoso Ltd", "850");
            return profiles;
        });
    }

    private static void seed(Map<String, Profile> profiles, String id, String name, String suffix,
                             String income, int years, String employer, String debt) {
        profiles.put(id, new Profile(id, name, suffix, new BigDecimal(income), years, employer,
                new BigDecimal(debt), List.of("paystubs.pdf", "id.pdf", "bank-statements.pdf"),
                "Synthetic demo customer master", "SIMULATED_RECORD", Instant.now(), 1));
    }

    public List<Profile> search(String query) {
        String term = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        return profiles().values().stream()
                .filter(p -> (p.applicantId() + " " + p.fullName()).toLowerCase(Locale.ROOT).contains(term))
                .sorted(Comparator.comparing(Profile::applicantId)).limit(30).toList();
    }

    public Profile get(String id) {
        Profile profile = profiles().get(id);
        if (profile == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Applicant not found");
        return profile;
    }

    public Profile register(Registration input) {
        var profiles = profiles();
        synchronized (profiles) {
            String name = input.fullName().trim().replaceAll("\\s+", " ");
            if (profiles.values().stream().anyMatch(p -> p.fullName().equalsIgnoreCase(name)
                    && p.ssnLast4().equals(input.ssnLast4()))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "A demo applicant with this name and identifier already exists. Search for the existing record.");
            }
            String id = "DEMO-" + UUID.randomUUID();
            Profile profile = new Profile(id, name, input.ssnLast4(), input.annualIncome(),
                    input.employmentYears(), input.employer().trim(), input.existingMonthlyDebt(), List.of(),
                    "Synthetic demo onboarding", "DECLARED_NOT_VERIFIED", Instant.now(), 1);
            profiles.put(id, profile);
            return profile;
        }
    }

    public LoanApplication application(String id, LoanDetails details) {
        Profile p = get(id);
        if (p.version() != details.profileVersion()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Applicant profile changed. Review it again.");
        }
        return new LoanApplication(p.applicantId(), p.fullName(), p.ssnLast4(), p.annualIncome(),
                p.employmentYears(), details.requestedAmount(), details.loanPurpose(), details.termMonths(),
                p.existingMonthlyDebt(), p.employer(), p.documents());
    }
}
