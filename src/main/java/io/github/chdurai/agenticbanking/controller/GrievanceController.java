package io.github.chdurai.agenticbanking.controller;

import jakarta.validation.Valid;
import io.github.chdurai.agenticbanking.grievance.Grievance;
import io.github.chdurai.agenticbanking.grievance.GrievanceClosure;
import io.github.chdurai.agenticbanking.grievance.GrievanceMemoryStore;
import io.github.chdurai.agenticbanking.grievance.GrievanceOrchestrator;
import io.github.chdurai.agenticbanking.grievance.GrievanceResolution;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/grievances")
public class GrievanceController {

    private final GrievanceOrchestrator orchestrator;
    private final GrievanceMemoryStore memory;
    private final io.github.chdurai.agenticbanking.cases.CaseService cases;

    public GrievanceController(GrievanceOrchestrator orchestrator, GrievanceMemoryStore memory, io.github.chdurai.agenticbanking.cases.CaseService cases) {
        this.orchestrator = orchestrator;
        this.memory = memory;
        this.cases = cases;
    }

    @PostMapping("/handle")
    public GrievanceResolution handle(@Valid @RequestBody Grievance grievance,
                                     @org.springframework.web.bind.annotation.RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return cases.runIdempotent("grievance", key == null || key.isBlank() ? java.util.UUID.randomUUID().toString() : key,
                grievance.grievanceId(), grievance, GrievanceResolution.class, "grievance.case.completed",
                () -> orchestrator.handle(grievance)).result();
    }

    @GetMapping("/history")
    public List<GrievanceResolution> history(@RequestParam(defaultValue = "20") int limit) {
        return memory.recent(limit);
    }

    @GetMapping("/{grievanceId}/closure")
    public GrievanceClosure closure(@PathVariable String grievanceId) {
        return memory.closure(grievanceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No closure recorded for grievance " + grievanceId));
    }

    @GetMapping("/sample")
    public Grievance sample() {
        return new Grievance("GRV-2001", "PRJ-ROADS-07",
                "Workers at the contractor camp have not been paid for six weeks and the camp has no "
                        + "clean drinking water. Several workers are now sick.",
                "WEB", "District 4 construction camp", false);
    }
}
