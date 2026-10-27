package io.github.chdurai.agenticbanking.cases;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import io.github.chdurai.agenticbanking.documents.DocumentRequest;
import io.github.chdurai.agenticbanking.documents.DocumentService;
import io.github.chdurai.agenticbanking.grievance.Grievance;
import io.github.chdurai.agenticbanking.grievance.GrievanceOrchestrator;
import io.github.chdurai.agenticbanking.guardrails.MultilingualSensitiveDetector;
import io.github.chdurai.agenticbanking.guardrails.InputGuardrail;
import io.github.chdurai.agenticbanking.incident.Incident;
import io.github.chdurai.agenticbanking.incident.IncidentOrchestrator;
import io.github.chdurai.agenticbanking.model.LoanApplication;
import io.github.chdurai.agenticbanking.orchestrator.LoanOrchestrator;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class FlowCaseDispatcher {
    private final ObjectMapper json;
    private final Validator validator;
    private final InputGuardrail guardrail;
    private final LoanOrchestrator loans;
    private final IncidentOrchestrator incidents;
    private final GrievanceOrchestrator grievances;
    private final DocumentService documents;

    public FlowCaseDispatcher(ObjectMapper json, Validator validator, InputGuardrail guardrail,
                              LoanOrchestrator loans, IncidentOrchestrator incidents,
                              GrievanceOrchestrator grievances, DocumentService documents) {
        this.json = json;
        this.validator = validator;
        this.guardrail = guardrail;
        this.loans = loans;
        this.incidents = incidents;
        this.grievances = grievances;
        this.documents = documents;
    }

    public Object prepare(String flow, JsonNode body) {
        Object request;
        try {
            request = switch (flow) {
                case "loan" -> json.treeToValue(body, LoanApplication.class);
                case "incident" -> json.treeToValue(body, Incident.class);
                case "grievance" -> json.treeToValue(body, Grievance.class);
                case "document" -> json.treeToValue(body, DocumentRequest.class);
                default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown flow");
            };
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid flow request");
        }
        if (request == null || !validator.validate(request).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid flow request");
        }
        return switch (request) {
            case LoanApplication a -> {
                var screened = guardrail.screen(a);
                if (screened.blocked() || !screened.flags().isEmpty()) throw blocked();
                yield screened.sanitizedApplication();
            }
            case Incident i -> new Incident(text("incidentId", i.incidentId(), 64),
                    text("title", i.title(), 500), text("description", i.description(), 20000),
                    text("service", i.service(), 500), text("environment", i.environment(), 500),
                    text("reporter", i.reporter(), 500),
                    i.tags() == null ? java.util.List.of() : i.tags().stream().map(t -> text("tag", t, 500)).toList());
            case Grievance g -> {
                String id = text("grievanceId", g.grievanceId(), 64);
                String project = text("projectId", g.projectId(), 64);
                if (MultilingualSensitiveDetector.isSensitive(g.description(), g.location())) {
                    // Preserve only the routing signal, never a survivor's report in the durable work queue.
                    yield new Grievance(id, project, "Sensitive case: sexual harassment",
                            "RESTRICTED_SOURCE", "withheld", true);
                }
                yield new Grievance(id, project, text("description", g.description(), 20000),
                        text("channel", g.channel(), 500), text("location", g.location(), 500), g.anonymous());
            }
            case DocumentRequest d -> new DocumentRequest(text("documentId", d.documentId(), 64),
                    text("title", d.title(), 500), text("content", d.content(), 100000));
            default -> throw new IllegalStateException("Unsupported flow request");
        };
    }

    private String text(String field, String value, int limit) {
        var screened = guardrail.screenText(field, value, limit);
        // Persisting sanitized text alone would lose the evidence that requires a human safety review.
        if (screened.blocked() || !screened.flags().isEmpty()) throw blocked();
        return screened.sanitized();
    }

    public Object execute(String flow, String request) {
        try {
            return switch (flow) {
                case "loan" -> loans.evaluate(json.readValue(request, LoanApplication.class));
                case "incident" -> incidents.handle(json.readValue(request, Incident.class));
                case "grievance" -> grievances.handle(json.readValue(request, Grievance.class));
                case "document" -> documents.analyze(json.readValue(request, DocumentRequest.class));
                default -> throw new IllegalStateException("Unknown persisted flow");
            };
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unreadable persisted request", e);
        }
    }

    public static ResponseStatusException blocked() {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Request cannot be processed automatically");
    }
}
