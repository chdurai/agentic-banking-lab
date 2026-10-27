package io.github.chdurai.agenticbanking.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import io.github.chdurai.agenticbanking.cases.CaseLink;
import io.github.chdurai.agenticbanking.cases.CaseLinkService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.net.URI;

@RestController
@RequestMapping("/api/v1")
public class CaseLinkController {
    public record Create(@NotBlank String toCaseId, @NotNull CaseLink.Type type) {}
    private final CaseLinkService links;
    public CaseLinkController(CaseLinkService links) { this.links = links; }

    @PostMapping("/cases/{id}/links")
    public ResponseEntity<CaseLink> create(@PathVariable String id, @Valid @RequestBody Create request) {
        var result = links.create(id, request.toCaseId(), request.type());
        return ResponseEntity.status(result.created() ? 201 : 200)
                .location(URI.create("/api/v1/case-links/" + result.link().id() + "/evidence"))
                .header("Idempotent-Replayed", Boolean.toString(!result.created())).body(result.link());
    }

    @GetMapping("/cases/{id}/links")
    public CaseLinkService.Page list(@PathVariable String id, @RequestParam(required = false) String cursor,
                                     @RequestParam(defaultValue = "20") int limit) {
        return links.list(id, cursor, limit);
    }

    @GetMapping("/case-links/{id}/evidence")
    public CaseLinkService.Evidence evidence(@PathVariable String id) { return links.evidence(id); }
}
