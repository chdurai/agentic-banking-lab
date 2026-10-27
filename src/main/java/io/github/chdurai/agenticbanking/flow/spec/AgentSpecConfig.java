package io.github.chdurai.agenticbanking.flow.spec;

import io.github.chdurai.agenticbanking.capability.CapabilityProperties;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Wires the {@link AgentSpecRegistry} (defaults + {@code platform.agents.<id>.*} overrides). */
@Configuration
public class AgentSpecConfig {

    @Bean
    public AgentSpecRegistry agentSpecRegistry(Environment env) {
        Map<String, AgentSpecOverride> overrides = Binder.get(env)
                .bind("platform.agents", Bindable.mapOf(String.class, AgentSpecOverride.class))
                .orElse(Map.of());
        return AgentSpecRegistry.withDefaults(overrides);
    }

    /**
     * Cross-checks specs against capability profiles: every spec'd agent needs a profile, and a spec may
     * not ask for more tools or tool calls than its profile grants.
     */
    @Component
    static class AgentSpecStartupValidator implements SmartInitializingSingleton {

        private final AgentSpecRegistry specs;
        private final CapabilityProperties capabilities;

        AgentSpecStartupValidator(AgentSpecRegistry specs, CapabilityProperties capabilities) {
            this.specs = specs;
            this.capabilities = capabilities;
        }

        @Override
        public void afterSingletonsInstantiated() {
            List<String> problems = check(specs, capabilities);
            if (!problems.isEmpty()) {
                throw new IllegalStateException("Agent spec validation failed: " + String.join("; ", problems));
            }
        }

        static List<String> check(AgentSpecRegistry specs, CapabilityProperties capabilities) {
            List<String> problems = new ArrayList<>();
            for (AgentSpec s : specs.all()) {
                CapabilityProperties.ProfileSpec profile = capabilities.getProfiles().get(s.agentId());
                if (profile == null) {
                    problems.add(s.agentId() + ": no capability profile");
                    continue;
                }
                for (String tool : s.capabilities()) {
                    if (!profile.getTools().contains(tool)) {
                        problems.add(s.agentId() + ": capability '" + tool + "' is not in its capability profile");
                    }
                }
                if (s.maxToolCalls() > profile.getMaxToolCalls()) {
                    problems.add(s.agentId() + ": maxToolCalls " + s.maxToolCalls()
                            + " exceeds its capability profile (" + profile.getMaxToolCalls() + ")");
                }
            }
            return problems;
        }
    }
}
