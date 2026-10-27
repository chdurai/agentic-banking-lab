package io.github.chdurai.agenticbanking.tools;

import io.github.chdurai.agenticbanking.tools.port.IncomeVerificationPort;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * Mock income/employment verification. In production, wire to Plaid, The Work Number, or payroll providers.
 */
@Component
public class IncomeVerificationTools implements IncomeVerificationPort {

    private final io.github.chdurai.agenticbanking.capability.CapabilityGuard capabilities;
    private final ToolCallAuditor auditor;

    public IncomeVerificationTools() {
        this(io.github.chdurai.agenticbanking.capability.CapabilityGuard.permissive(),
                new ToolCallAuditor(new io.github.chdurai.agenticbanking.ops.PlatformEventPublisher(event -> { })));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public IncomeVerificationTools(io.github.chdurai.agenticbanking.capability.CapabilityGuard capabilities, ToolCallAuditor auditor) {
        this.capabilities = capabilities;
        this.auditor = auditor;
    }

    @Tool(description = "Verify stated employment and income for an applicant against employer payroll records. "
            + "Returns verification status and verified annual income.")
    public IncomeVerificationResult verifyEmployment(String applicantId, String employer, double statedAnnualIncome) {
        return verify(new IncomeVerificationRequest(applicantId, employer, statedAnnualIncome));
    }

    @Override
    public IncomeVerificationResult verify(IncomeVerificationRequest request) {
        capabilities.checkTool("income.verify-employment");
        return auditor.audited("income.verify-employment", request, () -> {
            if (request.employer() == null || request.employer().isBlank()) {
                return new IncomeVerificationResult(request.applicantId(), null, false,
                        "no employer provided", 0, false);
            }
            long verified = Math.round(request.statedAnnualIncome() * 0.97);
            return new IncomeVerificationResult(request.applicantId(), request.employer(), true,
                    null, verified, true);
        });
    }

}
