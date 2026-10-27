package io.github.chdurai.agenticbanking.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import io.github.chdurai.agenticbanking.applicant.DemoApplicantDirectory;
import io.github.chdurai.agenticbanking.cases.AsyncCaseService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

/** Uses the existing /api/loans authorization boundary and case worker. */
@RestController
@RequestMapping("/api/loans/applicants")
public class ApplicantController {
    private final DemoApplicantDirectory directory;
    private final AsyncCaseService cases;
    private final CaseController views;
    private final ObjectMapper json;

    public ApplicantController(DemoApplicantDirectory directory, AsyncCaseService cases,
                               CaseController views, ObjectMapper json) {
        this.directory = directory;
        this.cases = cases;
        this.views = views;
        this.json = json;
    }

    @GetMapping
    public List<DemoApplicantDirectory.Profile> search(@RequestParam(defaultValue = "") String query) {
        return directory.search(query);
    }

    @GetMapping("/{id}")
    public DemoApplicantDirectory.Profile get(@PathVariable String id) { return directory.get(id); }

    @PostMapping
    public ResponseEntity<DemoApplicantDirectory.Profile> register(
            @Valid @RequestBody DemoApplicantDirectory.Registration input) {
        var profile = directory.register(input);
        return ResponseEntity.created(URI.create("/api/loans/applicants/" + profile.applicantId())).body(profile);
    }

    @PostMapping("/{id}/cases")
    public ResponseEntity<CaseController.View> submit(@PathVariable String id,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody DemoApplicantDirectory.LoanDetails details) {
        var application = directory.application(id, details);
        var claim = cases.submit("loan", key, json.valueToTree(application));
        return ResponseEntity.accepted().location(URI.create("/api/v1/cases/" + claim.record().caseId()))
                .header("Retry-After", "1")
                .header("Idempotent-Replayed", Boolean.toString(!claim.created()))
                .body(views.view(claim.record()));
    }
}
