package io.github.chdurai.agenticbanking.flow;

import io.github.chdurai.agenticbanking.flow.prompt.PromptBuilder;
import io.github.chdurai.agenticbanking.flow.spec.AgentSpecRegistry;
import io.github.chdurai.agenticbanking.flow.state.FindingMapper;
import io.github.chdurai.agenticbanking.guardrails.InputGuardrail;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Bundle of the typed-state collaborators an orchestrator hands to its per-case context. */
@Component
public class FlowKit {

    private final FlowProperties props;
    private final FindingMapper mapper;
    private final PromptBuilder prompts;

    private final AgentSpecRegistry specs;

    /** Live-observability sink handed to every case state; absent in legacy/non-Spring mode. */
    private io.github.chdurai.agenticbanking.ops.AgentActivityHub activity;

    @Autowired
    public FlowKit(FlowProperties props, FindingMapper mapper, PromptBuilder prompts, AgentSpecRegistry specs) {
        this.props = props;
        this.mapper = mapper;
        this.prompts = prompts;
        this.specs = specs;
    }

    @Autowired(required = false)
    void setActivity(io.github.chdurai.agenticbanking.ops.AgentActivityHub activity) {
        this.activity = activity;
    }

    public io.github.chdurai.agenticbanking.ops.AgentActivityHub activity() {
        return activity;
    }

    public FlowKit(FlowProperties props, FindingMapper mapper, PromptBuilder prompts) {
        this(props, mapper, prompts, AgentSpecRegistry.withDefaults());
    }

    /** Legacy-mode kit for code paths that build a context without Spring. */
    public static FlowKit legacy() {
        FlowProperties p = new FlowProperties();
        return new FlowKit(p, new FindingMapper(new InputGuardrail()), new PromptBuilder(p));
    }

    public boolean v2() { return props.v2(); }
    public boolean challengeV2() { return props.challengeV2(); }
    public int autonomyLevel() { return props.getAutonomyLevel(); }
    public boolean dag() { return props.dag(); }
    public FlowProperties props() { return props; }
    public AgentSpecRegistry specs() { return specs; }
    public FindingMapper mapper() { return mapper; }
    public PromptBuilder prompts() { return prompts; }
}
