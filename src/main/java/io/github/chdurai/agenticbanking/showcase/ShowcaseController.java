package io.github.chdurai.agenticbanking.showcase;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chdurai.agenticbanking.cases.AsyncCaseService;
import io.github.chdurai.agenticbanking.controller.CaseController;
import io.github.chdurai.agenticbanking.model.LoanApplication;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@Profile("showcase")
@RequestMapping("/api/showcase")
public class ShowcaseController {
    private final AsyncCaseService cases;
    private final CaseController views;
    private final ObjectMapper json;
    public ShowcaseController(AsyncCaseService cases, CaseController views, ObjectMapper json) {
        this.cases = cases; this.views = views; this.json = json;
    }

    @GetMapping
    public Map<String, Object> mode() {
        cases.authorize("loan");
        return Map.of("mode", "SCRIPTED", "data", "SYNTHETIC_ONLY", "version", "loan-fixtures-v1",
                "scenarios", List.of("complete", "missing", "conflicting", "fault-hostile", "fault-duplicate", "fault-timeout", "fault-cancel"),
                "usage", "Synthetic token counts; no provider charge", "recovery", "Local H2 only");
    }

    @PostMapping("/{scenario}")
    public CaseController.View launch(@PathVariable String scenario, @RequestHeader("Idempotency-Key") String key) {
        cases.authorize("loan");
        return views.view(cases.submit("loan", key, json.valueToTree(fixture(scenario))).record());
    }

    public static LoanApplication fixture(String scenario) {
        List<String> documents = switch (scenario) {
            case "complete" -> List.of("synthetic:income-complete:v1");
            case "missing" -> List.of("synthetic:income-missing:v1");
            case "conflicting" -> List.of("synthetic:income-conflict:v1");
            case "fault-hostile", "fault-duplicate", "fault-timeout", "fault-cancel" ->
                    List.of("synthetic:income-missing:v1", "synthetic:" + scenario + ":v1");
            default -> throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "Unknown synthetic scenario");
        };
        return new LoanApplication("SHOWCASE-" + scenario, "Synthetic Showcase Applicant", "1111",
                new BigDecimal("145000"), 8, new BigDecimal("15000"), "Synthetic home improvements",
                60, new BigDecimal("1200"), "Synthetic Employer", documents);
    }
}
