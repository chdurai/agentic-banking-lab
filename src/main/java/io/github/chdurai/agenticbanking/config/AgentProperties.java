package io.github.chdurai.agenticbanking.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

@ConfigurationProperties(prefix = "agents")
public class AgentProperties {

    /** agent name -> NIM model id */
    private Map<String, String> models = new HashMap<>();

    private String fallbackModel = "meta/llama-3.3-70b-instruct";

    private int failFastThreshold = 85;

    /**
     * Use the LLM planner instead of the deterministic rule-based plan. Off by default: the standard
     * loan pipeline is fixed by regulation, so a model call there adds cost and variance, not value.
     */
    private boolean plannerEnabled = false;

    /** RAG retrieval mode for the compliance agent: keyword | vector. */
    private String ragMode = "keyword";

    /** Minimum IDF-weighted retrieval score (WI-08); weak boilerplate-only matches are dropped. */
    private double ragMinScore = 1.0;

    /** Max decision revision passes after critic feedback. */
    private int maxRevisions = 2;

    /** model id -> USD price per 1K tokens (illustrative; used for FinOps estimates). */
    private Map<String, Pricing> pricing = new HashMap<>();

    /**
     * Per-agent tool mode (WI-05): deterministic-evidence | bounded-tool-use.
     * Default keeps today's behavior unless explicitly switched on.
     */
    private Map<String, String> toolUseMode = new HashMap<>();

    public static class Pricing {
        private double inputPer1k;
        private double outputPer1k;

        public double getInputPer1k() {
            return inputPer1k;
        }

        public void setInputPer1k(double inputPer1k) {
            this.inputPer1k = inputPer1k;
        }

        public double getOutputPer1k() {
            return outputPer1k;
        }

        public void setOutputPer1k(double outputPer1k) {
            this.outputPer1k = outputPer1k;
        }
    }

    public Map<String, Pricing> getPricing() {
        return pricing;
    }

    public void setPricing(Map<String, Pricing> pricing) {
        this.pricing = pricing;
    }

    public Map<String, String> getModels() {
        return models;
    }

    public void setModels(Map<String, String> models) {
        this.models = models;
    }

    public String getFallbackModel() {
        return fallbackModel;
    }

    public void setFallbackModel(String fallbackModel) {
        this.fallbackModel = fallbackModel;
    }

    public int getFailFastThreshold() {
        return failFastThreshold;
    }

    public void setFailFastThreshold(int failFastThreshold) {
        this.failFastThreshold = failFastThreshold;
    }

    public boolean isPlannerEnabled() {
        return plannerEnabled;
    }

    public void setPlannerEnabled(boolean plannerEnabled) {
        this.plannerEnabled = plannerEnabled;
    }

    public String getRagMode() {
        return ragMode;
    }

    public void setRagMode(String ragMode) {
        this.ragMode = ragMode;
    }

    public double getRagMinScore() {
        return ragMinScore;
    }

    public void setRagMinScore(double ragMinScore) {
        this.ragMinScore = ragMinScore;
    }

    public int getMaxRevisions() {
        return maxRevisions;
    }

    public void setMaxRevisions(int maxRevisions) {
        this.maxRevisions = maxRevisions;
    }

    public Map<String, String> getToolUseMode() {
        return toolUseMode;
    }

    public void setToolUseMode(Map<String, String> toolUseMode) {
        this.toolUseMode = toolUseMode;
    }

    private Anomaly anomaly = new Anomaly();

    public Anomaly getAnomaly() {
        return anomaly;
    }

    public void setAnomaly(Anomaly anomaly) {
        this.anomaly = anomaly;
    }

    /** Telemetry-driven incident detection tuning. */
    public static class Anomaly {
        private boolean enabled = true;
        private long intervalMs = 30000;
        private int minSamples = 20;
        private double errorRateThreshold = 0.25;
        private int cooldownMinutes = 5;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getIntervalMs() {
            return intervalMs;
        }

        public void setIntervalMs(long intervalMs) {
            this.intervalMs = intervalMs;
        }

        public int getMinSamples() {
            return minSamples;
        }

        public void setMinSamples(int minSamples) {
            this.minSamples = minSamples;
        }

        public double getErrorRateThreshold() {
            return errorRateThreshold;
        }

        public void setErrorRateThreshold(double errorRateThreshold) {
            this.errorRateThreshold = errorRateThreshold;
        }

        public int getCooldownMinutes() {
            return cooldownMinutes;
        }

        public void setCooldownMinutes(int cooldownMinutes) {
            this.cooldownMinutes = cooldownMinutes;
        }
    }

    public String modelFor(String agentName) {
        return models.getOrDefault(agentName, fallbackModel);
    }

    public String toolModeFor(String agentName) {
        return toolUseMode.getOrDefault(agentName, "deterministic-evidence");
    }
}
