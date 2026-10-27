package io.github.chdurai.agenticbanking.showcase;

import io.github.chdurai.agenticbanking.model.LoanApplication;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Fixed per-case faults; no global switches, arbitrary delays or process controls. */
@Component
@Profile("showcase")
public final class ShowcaseFaults {
    private static final Map<String, String> FAULTS = Map.of(
            "synthetic:fault-hostile:v1", "HOSTILE",
            "synthetic:fault-duplicate:v1", "DUPLICATE",
            "synthetic:fault-timeout:v1", "TOOL_TIMEOUT",
            "synthetic:fault-cancel:v1", "CANCEL_WINDOW");

    public ShowcaseFaults(Environment environment) {
        if (!ShowcaseConfiguration.isLocal(environment))
            throw new IllegalStateException("Synthetic faults require the loopback-only scripted showcase");
    }

    public String fault(LoanApplication application) {
        if (application.documents() == null) return "NONE";
        var selected = application.documents().stream().filter(FAULTS::containsKey).toList();
        if (selected.size() > 1) throw new IllegalArgumentException("Only one versioned synthetic fault per case");
        return selected.isEmpty() ? "NONE" : FAULTS.get(selected.getFirst());
    }

    public void beforeTool(LoanApplication application) {
        if ("TOOL_TIMEOUT".equals(fault(application))) {
            try { Thread.sleep(6000); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Synthetic timeout interrupted before adapter read", e);
            }
        }
    }
}
