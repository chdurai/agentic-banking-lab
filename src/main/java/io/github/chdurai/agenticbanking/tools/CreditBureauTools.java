package io.github.chdurai.agenticbanking.tools;

import io.github.chdurai.agenticbanking.tools.port.CreditBureauPort;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Mock credit bureau. In production, wire to Experian/Equifax/TransUnion APIs.
 * Deterministic data so the demo is reproducible.
 */
@Component
public class CreditBureauTools implements CreditBureauPort {

    private final io.github.chdurai.agenticbanking.capability.CapabilityGuard capabilities;
    private final ToolCallAuditor auditor;

    public CreditBureauTools() {
        this(io.github.chdurai.agenticbanking.capability.CapabilityGuard.permissive(),
                new ToolCallAuditor(new io.github.chdurai.agenticbanking.ops.PlatformEventPublisher(event -> { })));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public CreditBureauTools(io.github.chdurai.agenticbanking.capability.CapabilityGuard capabilities, ToolCallAuditor auditor) {
        this.capabilities = capabilities;
        this.auditor = auditor;
    }

    private record BureauProfile(int ficoScore, int openAccounts, int delinquencies,
                                 double utilizationPct, int hardInquiries12m, String oldestLineYears) {}

    private static final Map<String, BureauProfile> PROFILES = Map.of(
            "A001", new BureauProfile(782, 6, 0, 18.5, 1, "12"),
            "A002", new BureauProfile(648, 11, 2, 71.0, 5, "6"),
            "A003", new BureauProfile(705, 4, 0, 32.0, 0, "9")
    );

    @Tool(description = "Look up an applicant's credit bureau report by applicant ID. "
            + "Returns FICO score, open accounts, delinquencies, utilization, and recent inquiries.")
    public CreditReport getCreditReport(String applicantId) {
        return creditReport(applicantId);
    }

    @Override
    public CreditReport creditReport(String applicantId) {
        capabilities.checkTool("bureau.credit-report");
        return auditor.audited("bureau.credit-report", applicantId, () -> {
            BureauProfile p = PROFILES.getOrDefault(applicantId,
                    new BureauProfile(690, 5, 0, 45.0, 2, "5"));
            return new CreditReport(applicantId, p.ficoScore(), p.openAccounts(), p.delinquencies(),
                    p.utilizationPct(), p.hardInquiries12m(), p.oldestLineYears());
        });
    }

    @Tool(description = "Check whether the applicant has any bankruptcies, foreclosures, or charge-offs on file.")
    public PublicRecords getPublicRecords(String applicantId) {
        return publicRecords(applicantId);
    }

    @Override
    public PublicRecords publicRecords(String applicantId) {
        capabilities.checkTool("bureau.public-records");
        return auditor.audited("bureau.public-records", applicantId, () -> {
            if ("A002".equals(applicantId)) {
                return new PublicRecords("A002", 0, 0, 1, "$2,300 retail card charged off 2023");
            }
            return new PublicRecords(applicantId, 0, 0, 0, null);
        });
    }
}
