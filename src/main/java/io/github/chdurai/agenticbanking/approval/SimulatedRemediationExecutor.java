package io.github.chdurai.agenticbanking.approval;

import io.github.chdurai.agenticbanking.incident.RemediationPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Dry-run executor: records what would run; never touches any system. */
@Component
public class SimulatedRemediationExecutor implements RemediationExecutor {

    private static final Logger log = LoggerFactory.getLogger(SimulatedRemediationExecutor.class);

    @Override
    public List<String> execute(String incidentId, List<RemediationPlan.Step> steps) {
        List<String> out = new ArrayList<>();
        int n = 1;
        for (RemediationPlan.Step step : steps == null ? List.<RemediationPlan.Step>of() : steps) {
            String detail = step.params().isEmpty()
                    ? (step.command() == null ? "{}" : step.command())
                    : step.params().toString();
            String line = "[SIMULATED] step %d (%s): %s %s".formatted(n++, step.riskLevel(),
                    step.action(), detail);
            log.info("[executor] {} {}", incidentId, line);
            out.add(line);
        }
        return out;
    }
}
