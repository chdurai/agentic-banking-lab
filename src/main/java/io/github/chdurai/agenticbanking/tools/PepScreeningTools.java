package io.github.chdurai.agenticbanking.tools;

import io.github.chdurai.agenticbanking.tools.port.PepScreeningPort;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Mock PEP screening adapter (typed port).
 */
@Component
public class PepScreeningTools implements PepScreeningPort {

    private final io.github.chdurai.agenticbanking.capability.CapabilityGuard capabilities;
    private final ToolCallAuditor auditor;

    public PepScreeningTools() {
        this(io.github.chdurai.agenticbanking.capability.CapabilityGuard.permissive(),
                new ToolCallAuditor(new io.github.chdurai.agenticbanking.ops.PlatformEventPublisher(event -> { })));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PepScreeningTools(io.github.chdurai.agenticbanking.capability.CapabilityGuard capabilities, ToolCallAuditor auditor) {
        this.capabilities = capabilities;
        this.auditor = auditor;
    }

    @Tool(description = "Check whether a person is a Politically Exposed Person (PEP).")
    public PepResult checkPepStatus(String fullName) {
        return checkPep(new PepRequest(fullName));
    }

    @Override
    public PepResult checkPep(PepRequest request) {
        capabilities.checkTool("sanctions.pep");
        return auditor.audited("sanctions.pep", request, () -> {
            boolean pep = normalize(request.fullName()).contains("minister");
            return new PepResult(request.fullName(), pep, pep ? "PEP-MATCH-001" : "NONE");
        });
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String lower = value.toLowerCase(Locale.ROOT).trim();
        String decomposed = Normalizer.normalize(lower, Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}", "")
                .replaceAll("[^a-z0-9\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
