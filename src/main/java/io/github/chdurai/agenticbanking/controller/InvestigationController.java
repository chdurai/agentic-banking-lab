package io.github.chdurai.agenticbanking.controller;

import io.github.chdurai.agenticbanking.cases.AsyncCaseService;
import io.github.chdurai.agenticbanking.investigation.InvestigationStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@ConditionalOnProperty(name = "platform.loan.investigation.enabled", havingValue = "true")
@RequestMapping("/api/v1/cases/{id}/investigation")
public class InvestigationController {
    private final InvestigationStore store;
    private final AsyncCaseService cases;
    private final io.github.chdurai.agenticbanking.cases.CaseStore caseStore;
    private final com.fasterxml.jackson.databind.ObjectMapper json;
    private final io.github.chdurai.agenticbanking.investigation.DecisionLaboratory laboratory;
    private final io.github.chdurai.agenticbanking.investigation.ReviewOutcomeReader outcomes;
    public InvestigationController(InvestigationStore store, AsyncCaseService cases, io.github.chdurai.agenticbanking.cases.CaseStore caseStore,
                                   com.fasterxml.jackson.databind.ObjectMapper json, io.github.chdurai.agenticbanking.investigation.DecisionLaboratory laboratory,
                                   io.github.chdurai.agenticbanking.investigation.ReviewOutcomeReader outcomes) {
        this.store = store; this.cases = cases; this.caseStore = caseStore; this.json = json;
        this.laboratory = laboratory;
        this.outcomes = outcomes;
    }

    @GetMapping
    public Map<String, Object> get(@PathVariable String id) {
        requireCase(id);
        return store.view(id);
    }

    @PostMapping("/cancel")
    public Map<String, String> cancel(@PathVariable String id) {
        requireCase(id);
        store.cancel(id);
        return Map.of("status", "CANCELLATION_REQUESTED", "effect", "No banking mutation");
    }

    @GetMapping("/replay")
    public Map<String, Object> replay(@PathVariable String id) {
        requireCase(id);
        return Map.of("mode", "RECORDED_PLAYBACK", "synthetic", true,
                "providerCalls", 0, "toolCalls", 0, "mutationCalls", 0,
                "passport", store.passport(id));
    }

    @PostMapping("/passport")
    public Map<String, Object> freeze(@PathVariable String id) {
        var row = requireCase(id);
        return store.freeze(id, row.resultJson());
    }

    @GetMapping("/passport")
    public Map<String, Object> passport(@PathVariable String id) {
        requireCase(id);
        return store.passport(id);
    }

    @GetMapping("/comparison")
    public Map<String, Object> compare(@PathVariable String id, @RequestParam(required = false) Integer termMonths) {
        requireCase(id);
        return laboratory.compare(id, termMonths);
    }

    @GetMapping("/review-outcome")
    public Map<String, Object> reviewOutcome(@PathVariable String id) {
        requireCase(id);
        return outcomes.view(id);
    }

    private io.github.chdurai.agenticbanking.cases.CaseRecord requireCase(String id) {
        var row = caseStore.find(id).filter(record -> "loan".equals(record.flow())).orElseThrow(() ->
                new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Loan case not found"));
        cases.authorize(row.flow());
        if (row.resultJson() != null) {
            try {
                if ("COMPLIANCE_HOLD".equals(json.readTree(row.resultJson()).path("decision").asText())
                        && !io.github.chdurai.agenticbanking.security.CallerContext.current().roles().contains(io.github.chdurai.agenticbanking.approval.Roles.PLATFORM_ADMIN)
                        && !io.github.chdurai.agenticbanking.security.CallerContext.current().roles().contains(io.github.chdurai.agenticbanking.approval.Roles.COMPLIANCE_OFFICER)) {
                    throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN, "Restricted compliance evidence");
                }
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalStateException("Unreadable case result", e);
            }
        }
        return row;
    }
}
