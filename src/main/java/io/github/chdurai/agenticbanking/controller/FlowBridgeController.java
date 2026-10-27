package io.github.chdurai.agenticbanking.controller;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.chdurai.agenticbanking.cases.AsyncCaseService;
import io.github.chdurai.agenticbanking.cases.CaseStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.Map;

/** A2A-style flow bridge, deliberately not a claim of implementing the complete A2A protocol. */
@RestController
public class FlowBridgeController {
    public record Task(String id, String skill, String state, CaseController.View caseView) {}
    private final AsyncCaseService cases;
    private final CaseController api;

    public FlowBridgeController(AsyncCaseService cases, CaseController api) {
        this.cases = cases;
        this.api = api;
    }

    @GetMapping("/.well-known/agent-card.json")
    public Map<String, Object> card() {
        return Map.of("name", "Agentic Banking Flows", "version", "1.0",
                "protocol", "a2a-style-flow-bridge-v1",
                "authentication", Map.of("scheme", "bearer", "tenantBinding", "credential",
                        "authorization", "tenant-scoped flow role; no actor or tenant accepted in task body"),
                "skills", List.of("loan", "incident", "grievance", "document").stream()
                        .map(flow -> Map.of("id", flow, "submit", "/api/v1/a2a/" + flow + "/tasks",
                                "inputSchema", "/api/v1/a2a/" + flow + "/schema",
                                "outputSchema", "/api/v1/a2a/" + flow + "/output-schema")).toList(),
                "poll", "/api/v1/a2a/tasks/{id}",
                "caseLinks", Map.of("create", "/api/v1/cases/{id}/links", "evidence", "/api/v1/case-links/{id}/evidence"),
                "capabilities", Map.of("streaming", false, "pushNotifications", false));
    }

    @GetMapping("/api/v1/a2a/{flow}/output-schema")
    public Map<String, Object> outputSchema(@PathVariable String flow) {
        AsyncCaseService.requireFlow(flow);
        cases.authorize(flow);
        return Map.of("$schema", "https://json-schema.org/draft/2020-12/schema", "type", "object",
                "required", List.of("id", "skill", "state", "caseView"),
                "properties", Map.of(
                        "id", Map.of("type", "string"),
                        "skill", Map.of("const", flow),
                        "state", Map.of("enum", List.of("submitted", "working", "input-required", "completed", "rejected", "canceled", "failed")),
                        "caseView", Map.of("type", "object", "required", List.of("caseId", "flow", "status", "version"),
                                "properties", Map.of("caseId", Map.of("type", "string"), "flow", Map.of("const", flow),
                                        "status", Map.of("enum", java.util.Arrays.stream(CaseStatus.values()).map(Enum::name).toList()),
                                        "version", Map.of("type", "integer", "minimum", 0),
                                        "result", Map.of("type", List.of("object", "null"))))));
    }

    @GetMapping("/api/v1/a2a/{flow}/schema")
    public Map<String, Object> schema(@PathVariable String flow) {
        AsyncCaseService.requireFlow(flow);
        cases.authorize(flow);
        List<String> required = switch (flow) {
            case "loan" -> List.of("applicantId", "fullName", "ssnLast4", "annualIncome", "requestedAmount", "loanPurpose", "termMonths", "existingMonthlyDebt");
            case "incident" -> List.of("incidentId", "title", "description", "service");
            case "grievance" -> List.of("grievanceId", "projectId", "description");
            default -> List.of("documentId", "content");
        };
        var properties = new java.util.LinkedHashMap<String, Object>();
        required.forEach(name -> properties.put(name, Map.of("type",
                List.of("annualIncome", "requestedAmount", "existingMonthlyDebt").contains(name) ? "number"
                        : name.equals("termMonths") ? "integer" : "string")));
        switch (flow) {
            case "loan" -> {
                properties.put("annualIncome", Map.of("type", "number", "minimum", 0));
                properties.put("requestedAmount", Map.of("type", "number", "minimum", 1000));
                properties.put("existingMonthlyDebt", Map.of("type", "number", "minimum", 0));
                properties.put("termMonths", Map.of("type", "integer", "minimum", 6, "maximum", 360));
                properties.put("employmentYears", Map.of("type", "integer", "minimum", 0, "maximum", 60));
                properties.put("employer", Map.of("type", List.of("string", "null")));
                properties.put("documents", Map.of("type", List.of("array", "null"), "items", Map.of("type", "string")));
            }
            case "incident" -> {
                for (String name : List.of("environment", "reporter")) properties.put(name, Map.of("type", List.of("string", "null")));
                properties.put("tags", Map.of("type", List.of("array", "null"), "items", Map.of("type", "string")));
            }
            case "grievance" -> {
                properties.put("anonymous", Map.of("type", "boolean"));
                for (String name : List.of("location", "channel")) properties.put(name, Map.of("type", List.of("string", "null")));
            }
            default -> properties.put("title", Map.of("type", List.of("string", "null")));
        }
        for (String name : required) {
            if (properties.get(name).equals(Map.of("type", "string"))) {
                properties.put(name, Map.of("type", "string", "minLength", 1, "pattern", "\\S"));
            }
        }
        // The bridge accepts precisely the same request DTOs as the versioned case endpoint.
        return Map.of("$schema", "https://json-schema.org/draft/2020-12/schema",
                "type", "object", "required", required, "properties", properties, "additionalProperties", true);
    }

    @PostMapping("/api/v1/a2a/{flow}/tasks")
    public ResponseEntity<Task> submit(@PathVariable String flow,
                                       @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                       @RequestBody JsonNode request) {
        var claim = cases.submit(flow, key, request);
        var row = claim.record();
        return ResponseEntity.accepted().location(URI.create("/api/v1/a2a/tasks/" + row.caseId()))
                .header("Idempotent-Replayed", Boolean.toString(!claim.created()))
                .header("Retry-After", "1").body(new Task(row.caseId(), row.flow(), state(row.status()), api.view(row)));
    }

    @GetMapping("/api/v1/a2a/tasks/{id}")
    public ResponseEntity<Task> poll(@PathVariable String id) {
        var row = cases.get(id);
        return ResponseEntity.ok().eTag(Long.toString(row.version()))
                .body(new Task(row.caseId(), row.flow(), state(row.status()), api.view(row)));
    }

    public static String state(CaseStatus status) {
        return switch (status) {
            case OPEN -> "submitted";
            case RUNNING -> "working";
            case PENDING_APPROVAL -> "input-required";
            case COMPLETED, APPROVED -> "completed";
            case BLOCKED, REJECTED -> "rejected";
            case EXPIRED -> "canceled";
            case FAILED, EXECUTION_FAILED -> "failed";
        };
    }
}
