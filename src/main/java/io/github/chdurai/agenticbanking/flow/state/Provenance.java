package io.github.chdurai.agenticbanking.flow.state;

import java.util.List;

/** Where a finding came from. Mandatory: {@link CaseState#append} rejects findings without it. */
public record Provenance(String model, String promptVersion, String policyVersion, List<String> toolsCalled,
                         int tokensIn, int tokensOut, long latencyMs, boolean fallbackUsed) {
    public Provenance {
        toolsCalled = toolsCalled == null ? List.of() : List.copyOf(toolsCalled);
    }

    public static Provenance of(String model, boolean fallbackUsed, long latencyMs) {
        return new Provenance(model, "unversioned", null, List.of(), 0, 0, latencyMs, fallbackUsed);
    }
}
