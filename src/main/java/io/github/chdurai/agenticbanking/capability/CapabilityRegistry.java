package io.github.chdurai.agenticbanking.capability;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Loads and validates capability profiles. The startup self-check (design 7.7) refuses
 * to boot when a profile references an unknown tool, view or corpus, or has inconsistent
 * budgets — a typo in YAML must never silently widen an agent's privileges.
 */
@Component
public class CapabilityRegistry implements SmartInitializingSingleton {

    private final CapabilityProperties properties;
    private volatile Map<String, CapabilityProfile> profiles = Map.of();

    public CapabilityRegistry(CapabilityProperties properties) {
        this.properties = properties;
    }

    @Override
    public void afterSingletonsInstantiated() {
        validate();
    }

    /** Builds and validates the profile map; throws IllegalStateException on any problem. */
    public void validate() {
        Map<String, CapabilityProfile> built = new TreeMap<>();
        List<String> problems = new ArrayList<>();
        properties.getProfiles().forEach((agent, spec) -> {
            CapabilityProfile p = spec.toProfile(agent);
            built.put(agent, p);
            for (String tool : p.tools()) {
                if (!CapabilityCatalog.TOOLS.contains(tool)) {
                    problems.add(agent + ": unknown tool '" + tool + "'");
                }
            }
            for (String view : p.dataViews()) {
                if (!CapabilityCatalog.VIEWS.contains(view)) {
                    problems.add(agent + ": unknown data view '" + view + "'");
                }
            }
            for (String corpus : p.retrieval()) {
                if (!CapabilityCatalog.CORPORA.contains(corpus)) {
                    problems.add(agent + ": unknown retrieval corpus '" + corpus + "'");
                }
            }
            if (p.maxToolCalls() < 0 || p.maxTokensIn() <= 0 || p.maxTokensOut() <= 0 || p.timeoutMs() <= 0) {
                problems.add(agent + ": budgets must be positive");
            }
            if (p.maxToolCalls() > 0 && p.tools().isEmpty()) {
                problems.add(agent + ": maxToolCalls > 0 but no tools allow-listed");
            }
        });
        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "Capability profile validation failed: " + String.join("; ", problems));
        }
        this.profiles = Map.copyOf(built);
    }

    public Optional<CapabilityProfile> profile(String agentId) {
        return Optional.ofNullable(profiles.get(agentId));
    }

    public Map<String, CapabilityProfile> profiles() {
        return profiles;
    }

    public String version() {
        return properties.getVersion();
    }
}
