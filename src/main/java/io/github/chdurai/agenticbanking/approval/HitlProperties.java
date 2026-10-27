package io.github.chdurai.agenticbanking.approval;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * platform.hitl.*: auth "dev" accepts the body's decidedBy and ignores roles unless enforce-roles is true;
 * auth "required" demands an authenticated reviewer id and enforces roles and four-eyes.
 */
@ConfigurationProperties(prefix = "platform.hitl")
public class HitlProperties {
    private String auth = "dev";
    private boolean enforceRoles = false;
    private long defaultTtlHours = 24;
    private Map<String, Long> ttlHours = Map.of("incident", 4L, "compliance", 8L);
    private long sweepIntervalMs = 60_000;
    private double blindFirstSampleRate = 0.0;
    private Set<String> blindFirstFlows = Set.of("loan", "incident", "grievance", "document", "compliance");
    private int nonTrivialRiskMinScore = 60;

    public String getAuth() { return auth; }
    public void setAuth(String auth) { this.auth = auth; }
    public boolean isEnforceRoles() { return enforceRoles || authRequired(); }
    public void setEnforceRoles(boolean enforceRoles) { this.enforceRoles = enforceRoles; }
    public long getDefaultTtlHours() { return defaultTtlHours; }
    public void setDefaultTtlHours(long v) { this.defaultTtlHours = v; }
    public Map<String, Long> getTtlHours() { return ttlHours; }
    public void setTtlHours(Map<String, Long> ttlHours) { this.ttlHours = ttlHours; }
    public long getSweepIntervalMs() { return sweepIntervalMs; }
    public void setSweepIntervalMs(long v) { this.sweepIntervalMs = v; }
    public double getBlindFirstSampleRate() { return blindFirstSampleRate; }
    public void setBlindFirstSampleRate(double blindFirstSampleRate) { this.blindFirstSampleRate = blindFirstSampleRate; }
    public Set<String> getBlindFirstFlows() { return blindFirstFlows; }
    public void setBlindFirstFlows(Set<String> blindFirstFlows) { this.blindFirstFlows = blindFirstFlows; }
    public int getNonTrivialRiskMinScore() { return nonTrivialRiskMinScore; }
    public void setNonTrivialRiskMinScore(int nonTrivialRiskMinScore) { this.nonTrivialRiskMinScore = nonTrivialRiskMinScore; }

    public boolean authRequired() { return "required".equalsIgnoreCase(auth); }

    public long ttlHoursFor(String flow) {
        return ttlHours.getOrDefault(flow, defaultTtlHours);
    }

    public boolean sampleBlindFirst(String flow, String subjectId) {
        if (blindFirstSampleRate <= 0 || blindFirstFlows == null || !blindFirstFlows.contains(flow)) {
            return false;
        }
        double rate = Math.min(1.0, blindFirstSampleRate);
        int bucket = Math.floorMod(Objects.hash(flow, subjectId), 10_000);
        return bucket < (int) Math.round(rate * 10_000);
    }
}
