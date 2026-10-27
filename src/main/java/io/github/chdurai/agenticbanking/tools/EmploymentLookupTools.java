package io.github.chdurai.agenticbanking.tools;

import io.github.chdurai.agenticbanking.tools.port.EmploymentPort;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * Mock employment lookup adapter. Read-only and deterministic.
 */
@Component
public class EmploymentLookupTools implements EmploymentPort {

    private final io.github.chdurai.agenticbanking.capability.CapabilityGuard capabilities;
    private final ToolCallAuditor auditor;

    public EmploymentLookupTools(io.github.chdurai.agenticbanking.capability.CapabilityGuard capabilities, ToolCallAuditor auditor) {
        this.capabilities = capabilities;
        this.auditor = auditor;
    }

    @Tool(description = "Look up current employment status and tenure by applicant id.")
    public EmploymentRecord employmentStatus(String applicantId) {
        return employment(applicantId);
    }

    @Override
    public EmploymentRecord employment(String applicantId) {
        capabilities.checkTool("employment.lookup");
        return auditor.audited("employment.lookup", applicantId,
                () -> new EmploymentRecord(applicantId, "verified-employer", true, 36));
    }
}
