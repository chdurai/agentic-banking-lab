package io.github.chdurai.agenticbanking.controller;

import jakarta.validation.Valid;
import io.github.chdurai.agenticbanking.incident.AlertIngestionService;
import io.github.chdurai.agenticbanking.incident.AlertSignal;
import io.github.chdurai.agenticbanking.incident.Incident;
import io.github.chdurai.agenticbanking.incident.IncidentMemoryStore;
import io.github.chdurai.agenticbanking.incident.IncidentOrchestrator;
import io.github.chdurai.agenticbanking.incident.IncidentResolution;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/incidents")
public class IncidentController {

    private final IncidentOrchestrator orchestrator;
    private final IncidentMemoryStore memory;
    private final AlertIngestionService alerts;
    private final io.github.chdurai.agenticbanking.cases.CaseService cases;

    public IncidentController(IncidentOrchestrator orchestrator, IncidentMemoryStore memory,
                              AlertIngestionService alerts, io.github.chdurai.agenticbanking.cases.CaseService cases) {
        this.orchestrator = orchestrator;
        this.memory = memory;
        this.alerts = alerts;
        this.cases = cases;
    }

    @PostMapping("/handle")
    public IncidentResolution handle(@Valid @RequestBody Incident incident,
                                    @org.springframework.web.bind.annotation.RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return cases.runIdempotent("incident", key == null || key.isBlank() ? java.util.UUID.randomUUID().toString() : key,
                incident.incidentId(), incident, IncidentResolution.class, "incident.case.completed",
                () -> orchestrator.handle(incident)).result();
    }

    /** Alert-webhook path (WI-11): queue + worker ingestion with sliding-window dedupe. */
    @PostMapping("/alerts")
    public Map<String, Object> ingest(@Valid @RequestBody AlertSignal signal) {
        AlertIngestionService.Receipt r = alerts.ingest(signal);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("alertId", r.alertId());
        out.put("accepted", r.accepted());
        out.put("ignored", r.ignored());
        out.put("reason", r.reason());
        return out;
    }

    @GetMapping("/history")
    public List<IncidentResolution> history(@RequestParam(defaultValue = "20") int limit) {
        return memory.recent(limit);
    }

    @GetMapping("/alerts/{id}")
    public io.github.chdurai.agenticbanking.incident.JdbcAlertQueue.Receipt receipt(@org.springframework.web.bind.annotation.PathVariable String id) {
        casesForIncidentRole();
        return alerts.receipt(id).orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "Alert receipt not found"));
    }

    private static void casesForIncidentRole() {
        var roles = io.github.chdurai.agenticbanking.security.CallerContext.current().roles();
        if (!roles.contains(io.github.chdurai.agenticbanking.approval.Roles.PLATFORM_ADMIN) && !roles.contains(io.github.chdurai.agenticbanking.approval.Roles.INCIDENT_COMMANDER)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "Incident role required");
        }
    }

    /** Sample: a SEV3 DB connection-pool incident matching runbook RB-DB-001. */
    @GetMapping("/sample")
    public Incident sample() {
        return new Incident(
                "INC-1042",
                "Loan API timing out - connection pool exhausted",
                "Since 14:05 UTC the loan-service API returns HTTP 500 on ~30% of requests. "
                        + "HikariCP shows 50/50 active connections. Several connections idle in "
                        + "transaction for 12+ minutes. Started right after the 2.4.1 deploy.",
                "loan-service",
                "production",
                "oncall-sre",
                List.of("database", "latency"));
    }
}
