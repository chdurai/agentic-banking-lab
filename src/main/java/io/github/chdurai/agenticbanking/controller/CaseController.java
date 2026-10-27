package io.github.chdurai.agenticbanking.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chdurai.agenticbanking.cases.AsyncCaseService;
import io.github.chdurai.agenticbanking.cases.CaseRecord;
import io.github.chdurai.agenticbanking.cases.CaseStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class CaseController {
    public record View(String caseId, String flow, String status, long version, JsonNode result,
                       Instant createdAt, Instant updatedAt) {}
    public record Page(List<View> items, String nextCursor) {}
    public record Event(long sequence, String type, Instant at) {}
    private final AsyncCaseService cases;
    private final CaseStore store;
    private final ObjectMapper json;

    public CaseController(AsyncCaseService cases, CaseStore store, ObjectMapper json) {
        this.cases = cases;
        this.store = store;
        this.json = json;
    }

    @PostMapping("/{flow}/cases")
    public ResponseEntity<View> submit(@PathVariable String flow,
                                       @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                       @RequestBody JsonNode request) {
        var claim = cases.submit(flow, key, request);
        return ResponseEntity.accepted().location(URI.create("/api/v1/cases/" + claim.record().caseId()))
                .header("Idempotent-Replayed", Boolean.toString(!claim.created()))
                .header("Retry-After", "1").body(view(claim.record()));
    }

    @GetMapping("/cases/{id}")
    public ResponseEntity<View> get(@PathVariable String id) {
        CaseRecord row = cases.get(id);
        return ResponseEntity.ok().eTag(Long.toString(row.version())).body(view(row));
    }

    @GetMapping("/cases")
    public Page page(@RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit) {
        if (limit < 1 || limit > 100 || cursor != null && !cursor.matches("[a-fA-F0-9-]{36}")) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Invalid cursor or limit");
        }
        var rows = store.page(cursor, limit + 1);
        // Advance on stored rows, not returned rows; callers cannot use pagination to bypass flow-role filtering.
        var selected = rows.stream().limit(limit).filter(r ->
                io.github.chdurai.agenticbanking.security.CallerContext.current().roles().contains(io.github.chdurai.agenticbanking.approval.Roles.PLATFORM_ADMIN)
                        || io.github.chdurai.agenticbanking.security.CallerContext.current().roles().contains(io.github.chdurai.agenticbanking.approval.Roles.forFlow(r.flow()))).toList();
        return new Page(selected.stream().map(this::view).toList(),
                rows.size() > limit ? rows.get(limit - 1).caseId() : null);
    }

    @GetMapping("/cases/{id}/events")
    public List<Event> events(@PathVariable String id, @RequestParam(defaultValue = "0") long after,
                              @RequestParam(defaultValue = "50") int limit) {
        cases.get(id);
        if (after < 0 || limit < 1 || limit > 100) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "Invalid event cursor");
        }
        return store.events(id).stream().filter(e -> e.seq() > after).limit(limit)
                .map(e -> new Event(e.seq(), e.type(), e.at())).toList();
    }

    public View view(CaseRecord row) {
        JsonNode result = null;
        if (row.resultJson() != null) {
            try { result = json.readTree(row.resultJson()); }
            catch (JsonProcessingException e) { throw new IllegalStateException("Unreadable stored result", e); }
        }
        if (result != null && row.flow().equals("loan") && result.path("decision").asText().equals("COMPLIANCE_HOLD")) {
            result = json.createObjectNode().put("decision", "COMPLIANCE_HOLD")
                    .put("reasoning", "Referred for further review");
        }
        return new View(row.caseId(), row.flow(), row.status().name(), row.version(), result, row.createdAt(), row.updatedAt());
    }
}
