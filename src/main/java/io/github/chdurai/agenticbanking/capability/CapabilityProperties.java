package io.github.chdurai.agenticbanking.capability;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code platform.capabilities} — declarative capability profiles per agent (WI-20).
 */
@ConfigurationProperties(prefix = "platform.capabilities")
public class CapabilityProperties {

    /** Profile schema/version marker, recorded alongside audit events. */
    private String version = "v1";

    /** Keyed by agent id (the same id used in {@code AgentCallSpec} / {@code agents.models}). */
    private Map<String, ProfileSpec> profiles = new LinkedHashMap<>();

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public Map<String, ProfileSpec> getProfiles() {
        return profiles;
    }

    public void setProfiles(Map<String, ProfileSpec> profiles) {
        this.profiles = profiles;
    }

    public static class ProfileSpec {
        private List<String> dataViews = List.of();
        private List<String> tools = List.of();
        private List<String> retrieval = List.of();
        private int maxTokensIn = 8192;
        private int maxTokensOut = 2048;
        private int maxToolCalls = 0;
        private long timeoutMs = 60000;
        private String outputSchema = "";
        private boolean canRaiseStrictnessOnly = true;

        public CapabilityProfile toProfile(String agent) {
            return new CapabilityProfile(agent, dataViews, tools, retrieval, maxTokensIn,
                    maxTokensOut, maxToolCalls, timeoutMs, outputSchema, canRaiseStrictnessOnly);
        }

        public List<String> getDataViews() {
            return dataViews;
        }

        public void setDataViews(List<String> dataViews) {
            this.dataViews = dataViews;
        }

        public List<String> getTools() {
            return tools;
        }

        public void setTools(List<String> tools) {
            this.tools = tools;
        }

        public List<String> getRetrieval() {
            return retrieval;
        }

        public void setRetrieval(List<String> retrieval) {
            this.retrieval = retrieval;
        }

        public int getMaxTokensIn() {
            return maxTokensIn;
        }

        public void setMaxTokensIn(int maxTokensIn) {
            this.maxTokensIn = maxTokensIn;
        }

        public int getMaxTokensOut() {
            return maxTokensOut;
        }

        public void setMaxTokensOut(int maxTokensOut) {
            this.maxTokensOut = maxTokensOut;
        }

        public int getMaxToolCalls() {
            return maxToolCalls;
        }

        public void setMaxToolCalls(int maxToolCalls) {
            this.maxToolCalls = maxToolCalls;
        }

        public long getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(long timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public String getOutputSchema() {
            return outputSchema;
        }

        public void setOutputSchema(String outputSchema) {
            this.outputSchema = outputSchema;
        }

        public boolean isCanRaiseStrictnessOnly() {
            return canRaiseStrictnessOnly;
        }

        public void setCanRaiseStrictnessOnly(boolean canRaiseStrictnessOnly) {
            this.canRaiseStrictnessOnly = canRaiseStrictnessOnly;
        }
    }
}
