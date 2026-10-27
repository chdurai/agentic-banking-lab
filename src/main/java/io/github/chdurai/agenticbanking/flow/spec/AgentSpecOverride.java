package io.github.chdurai.agenticbanking.flow.spec;

import java.util.List;

/**
 * Config override of a built-in spec: {@code platform.agents.<agentId>.{mode,deadline-ms,critical,
 * challengeable,reads}}. Overrides are validated with the defaults at startup, so a bad {@code reads}
 * entry (unknown agent, cycle) fails the boot.
 */
public class AgentSpecOverride {

    private AgentSpec.Mode mode;
    private Long deadlineMs;
    private Boolean critical;
    private Integer challengeable;
    private List<String> reads;

    public AgentSpec.Mode getMode() { return mode; }
    public void setMode(AgentSpec.Mode mode) { this.mode = mode; }
    public Long getDeadlineMs() { return deadlineMs; }
    public void setDeadlineMs(Long deadlineMs) { this.deadlineMs = deadlineMs; }
    public Boolean getCritical() { return critical; }
    public void setCritical(Boolean critical) { this.critical = critical; }
    public Integer getChallengeable() { return challengeable; }
    public void setChallengeable(Integer challengeable) { this.challengeable = challengeable; }
    public List<String> getReads() { return reads; }
    public void setReads(List<String> reads) { this.reads = reads; }

    AgentSpec apply(AgentSpec base) {
        AgentSpec.Builder b = base.toBuilder();
        if (mode != null) b.mode(mode);
        if (deadlineMs != null) b.deadlineMs(deadlineMs);
        if (critical != null) b.critical(critical);
        if (challengeable != null) b.challengeable(challengeable);
        if (reads != null) b.reads(reads);
        return b.build();
    }
}
