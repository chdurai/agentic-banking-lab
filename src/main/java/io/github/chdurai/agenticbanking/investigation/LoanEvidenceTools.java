package io.github.chdurai.agenticbanking.investigation;

import io.github.chdurai.agenticbanking.capability.CapabilityGuard;
import io.github.chdurai.agenticbanking.model.LoanApplication;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Fixed read-only adapters: all subjects and inputs come from the server-held application. */
public class LoanEvidenceTools {
    private final CapabilityGuard capabilities;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private io.github.chdurai.agenticbanking.showcase.ShowcaseFaults faults;
    public LoanEvidenceTools(CapabilityGuard capabilities) { this.capabilities = capabilities; }

    public LoanInvestigator.Observation read(String id, String tool, LoanApplication application) {
        capabilities.checkTool(tool);
        if (faults != null) faults.beforeTool(application);
        Map<String, Object> evidence = switch (tool) {
            case "loan.documents.v1" -> Map.of("gap", LoanInvestigator.gap(application), "documentCount",
                    application.documents() == null ? 0 : application.documents().size(),
                    "incomeDocumentPresent", !"MISSING_INCOME".equals(LoanInvestigator.gap(application)),
                    "verifiedCustomerEvidence", false);
            case "loan.sources.v1" -> Map.of("gap", LoanInvestigator.gap(application),
                    "versions", List.of(
                            Map.of("version", "synthetic-income-v1", "annualIncome", application.annualIncome()),
                            Map.of("version", "synthetic-income-v2", "annualIncome",
                                    "CONFLICTING_INCOME".equals(LoanInvestigator.gap(application))
                                            ? application.annualIncome().add(new java.math.BigDecimal("20000")) : application.annualIncome())),
                    "conflict", "CONFLICTING_INCOME".equals(LoanInvestigator.gap(application)),
                    "verifiedCustomerEvidence", false);
            default -> throw new IllegalArgumentException("Unapproved tool");
        };
        return new LoanInvestigator.Observation(id, tool, "SYNTHETIC_ONLY", "loan-fixtures-v1", Instant.now().toString(), evidence);
    }
}
