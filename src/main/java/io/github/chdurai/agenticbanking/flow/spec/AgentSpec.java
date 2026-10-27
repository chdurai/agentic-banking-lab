package io.github.chdurai.agenticbanking.flow.spec;

import io.github.chdurai.agenticbanking.flow.state.Finding;

import java.util.List;
import java.util.Set;

/**
 * Declared contract of one agent (v3.1 §3.4). The prompt builder renders only {@code reads}; the plan
 * resolver derives DAG edges from them; the runtime enforces {@code deadlineMs} and {@code onFailure}.
 *
 * @param reads        agent ids this agent may see, in prompt rendering order
 * @param writes       finding kinds it may emit
 * @param capabilities tools it may use; empty means "whatever its capability profile allows"
 * @param maxTokens    0 inherits the capability profile
 * @param maxToolCalls 0 inherits the capability profile
 * @param challengeable how many CHALLENGEs it may receive (0 = never; WI-36 consumes this)
 * @param required     no plan may drop it
 * @param planned      false for agents run outside the DAG (the decision step)
 */
public record AgentSpec(
        String agentId,
        String flow,
        int version,
        List<String> reads,
        Set<Finding.Kind> writes,
        Set<String> capabilities,
        Mode mode,
        long deadlineMs,
        int maxTokens,
        int maxToolCalls,
        OnFailure onFailure,
        boolean critical,
        int challengeable,
        boolean required,
        boolean planned) {

    public enum Mode { LLM, DETERMINISTIC, HYBRID }

    /** DEGRADE: continue (the node reports UNAVAILABLE); HOLD: stop scheduling, route to a human; ABORT: stop. */
    public enum OnFailure { DEGRADE, HOLD, ABORT }

    public static final long DEFAULT_DEADLINE_MS = 120_000;

    public AgentSpec {
        reads = reads == null ? List.of() : List.copyOf(reads);
        writes = writes == null ? Set.of() : Set.copyOf(writes);
        capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
    }

    /** True when the node may call an LLM (counts against the flow's LLM-call cap). */
    public boolean usesLlm() {
        return mode != Mode.DETERMINISTIC;
    }

    public static Builder of(String flow, String agentId) {
        return new Builder(flow, agentId);
    }

    public Builder toBuilder() {
        return new Builder(flow, agentId).version(version).reads(reads).writes(writes).capabilities(capabilities)
                .mode(mode).deadlineMs(deadlineMs).maxTokens(maxTokens).maxToolCalls(maxToolCalls)
                .onFailure(onFailure).critical(critical).challengeable(challengeable)
                .required(required).planned(planned);
    }

    public static final class Builder {
        private final String flow;
        private final String agentId;
        private int version = 1;
        private List<String> reads = List.of();
        private Set<Finding.Kind> writes = Set.of(Finding.Kind.ASSESSMENT);
        private Set<String> capabilities = Set.of();
        private Mode mode = Mode.LLM;
        private long deadlineMs = DEFAULT_DEADLINE_MS;
        private int maxTokens;
        private int maxToolCalls;
        private OnFailure onFailure = OnFailure.DEGRADE;
        private boolean critical;
        private int challengeable;
        private boolean required;
        private boolean planned = true;

        private Builder(String flow, String agentId) {
            this.flow = flow;
            this.agentId = agentId;
        }

        public Builder version(int v) { this.version = v; return this; }
        public Builder reads(List<String> v) { this.reads = v; return this; }
        public Builder reads(String... v) { this.reads = List.of(v); return this; }
        public Builder writes(Set<Finding.Kind> v) { this.writes = v; return this; }
        public Builder capabilities(Set<String> v) { this.capabilities = v; return this; }
        public Builder mode(Mode v) { this.mode = v; return this; }
        public Builder deadlineMs(long v) { this.deadlineMs = v; return this; }
        public Builder maxTokens(int v) { this.maxTokens = v; return this; }
        public Builder maxToolCalls(int v) { this.maxToolCalls = v; return this; }
        public Builder onFailure(OnFailure v) { this.onFailure = v; return this; }
        public Builder critical(boolean v) { this.critical = v; return this; }
        public Builder challengeable(int v) { this.challengeable = v; return this; }
        public Builder required(boolean v) { this.required = v; return this; }
        public Builder planned(boolean v) { this.planned = v; return this; }

        public AgentSpec build() {
            return new AgentSpec(agentId, flow, version, reads, writes, capabilities, mode, deadlineMs, maxTokens,
                    maxToolCalls, onFailure, critical, challengeable, required, planned);
        }
    }
}
