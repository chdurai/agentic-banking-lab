package io.github.chdurai.agenticbanking.flow;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * platform.flow.state: "legacy" keeps the transcript handoff; "v2" uses typed findings and the PromptBuilder.
 * platform.flow.runtime: "legacy" keeps stage-barrier scheduling for the loan flow; "dag" schedules each agent
 * as soon as the agents it reads have finished (WI-35).
 * platform.flow.challenge: "legacy" keeps the one-shot critic loop; "v2" uses the bounded challenge protocol
 * (WI-36): max-rounds, max-challenges-per-finding and the autonomy-level (L0..L3) that decides which open
 * challenges force human review.
 */
@ConfigurationProperties(prefix = "platform.flow")
public class FlowProperties {
    private String state = "legacy";
    private int maxNarrativeChars = 600;
    private String runtime = "legacy";
    private long perNodeDeadlineMs = 120_000;
    private int maxPlanNodes = 8;
    private int maxLlmCalls = 6;
    private String challenge = "legacy";
    private int maxRounds = 2;
    private int maxChallengesPerFinding = 1;
    private int autonomyLevel = 2;

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public int getMaxNarrativeChars() { return maxNarrativeChars; }
    public void setMaxNarrativeChars(int v) { this.maxNarrativeChars = v; }

    public String getRuntime() { return runtime; }
    public void setRuntime(String runtime) { this.runtime = runtime; }
    public long getPerNodeDeadlineMs() { return perNodeDeadlineMs; }
    public void setPerNodeDeadlineMs(long v) { this.perNodeDeadlineMs = v; }
    public int getMaxPlanNodes() { return maxPlanNodes; }
    public void setMaxPlanNodes(int v) { this.maxPlanNodes = v; }
    public int getMaxLlmCalls() { return maxLlmCalls; }
    public void setMaxLlmCalls(int v) { this.maxLlmCalls = v; }

    public String getChallenge() { return challenge; }
    public void setChallenge(String challenge) { this.challenge = challenge; }
    public int getMaxRounds() { return maxRounds; }
    public void setMaxRounds(int v) { this.maxRounds = v; }
    public int getMaxChallengesPerFinding() { return maxChallengesPerFinding; }
    public void setMaxChallengesPerFinding(int v) { this.maxChallengesPerFinding = v; }
    public int getAutonomyLevel() { return autonomyLevel; }
    public void setAutonomyLevel(int v) { this.autonomyLevel = v; }

    public boolean challengeV2() {
        return "v2".equalsIgnoreCase(challenge);
    }

    public boolean dag() {
        return "dag".equalsIgnoreCase(runtime);
    }

    public boolean v2() {
        return "v2".equalsIgnoreCase(state);
    }
}
