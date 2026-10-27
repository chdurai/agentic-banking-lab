package io.github.chdurai.agenticbanking.incident;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chdurai.agenticbanking.approval.ActionSpec;
import io.github.chdurai.agenticbanking.approval.ApprovalActionHandler;
import io.github.chdurai.agenticbanking.approval.ApprovalRequest;
import io.github.chdurai.agenticbanking.approval.RemediationExecutor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Runs an approved remediation plan. The plan travels as data in the {@link ActionSpec}, never as a closure. */
@Component
public class RemediationActionHandler implements ApprovalActionHandler {

    public static final String TYPE = "incident.remediation";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RemediationExecutor executor;
    private final IncidentActionCatalog catalog;

    public RemediationActionHandler(RemediationExecutor executor, IncidentActionCatalog catalog) {
        this.executor = executor;
        this.catalog = catalog;
    }

    public static ActionSpec spec(String incidentId, List<RemediationPlan.Step> steps) {
        try {
            return new ActionSpec(TYPE, Map.of("incidentId", incidentId, "steps", MAPPER.writeValueAsString(steps)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public List<String> execute(ApprovalRequest request) {
        try {
            List<RemediationPlan.Step> steps = MAPPER.readValue(request.action().params().get("steps"),
                    new TypeReference<>() {});
            for (RemediationPlan.Step step : steps) {
                IncidentActionCatalog.Validation v = catalog.validate(step);
                if (!v.valid()) {
                    throw new IllegalStateException("unsafe action spec: " + v.reason());
                }
            }
            return executor.execute(request.action().params().get("incidentId"), steps);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("unreadable remediation plan", e);
        }
    }
}
