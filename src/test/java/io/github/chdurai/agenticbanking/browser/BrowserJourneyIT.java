package io.github.chdurai.agenticbanking.browser;

import io.github.chdurai.agenticbanking.integration.StubChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Opt-in real HTTP/browser journeys; the model is deterministic and never contacts a provider. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.ai.model.chat=none", "spring.ai.model.embedding=none",
        "spring.ai.mcp.client.enabled=false", "spring.ai.mcp.server.enabled=false",
        "agents.anomaly.enabled=false", "platform.persistence=memory",
        "platform.callers.required=false", "platform.hitl.auth=dev",
        "platform.hitl.enforce-roles=false", "platform.hitl.blind-first-sample-rate=1.0",
        "platform.cases.worker-enabled=true", "platform.cases.poll-ms=100",
        "agents.rag-mode=keyword"
})
@Import(BrowserJourneyIT.ModelConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BrowserJourneyIT {
    @LocalServerPort int port;

    @TestConfiguration
    static class ModelConfiguration {
        @Bean
        StubChatModel browserModel() {
            var model = new StubChatModel();
            model.fixed.put("incident-remediation", StubChatModel.remediation(true));
            return model;
        }
    }

    @Test
    void browserJourneysAgainstRealApplication() throws Exception {
        var python = System.getenv().getOrDefault("BROWSER_TEST_PYTHON", "python3");
        var scenario = System.getenv().getOrDefault("BROWSER_DEMO_SCENARIO", "all");
        var selector = switch (scenario) {
            case "all" -> "";
            case "existing-loan" -> "::test_seeded_applicant_real_loan";
            case "new-applicant" -> "::test_create_and_reuse_applicant";
            case "incident-approve" -> "::test_real_sample_submission[chromium-incident-APPROVE]";
            case "incident-reject" -> "::test_real_sample_submission[chromium-incident-REJECT]";
            case "grievance" -> "::test_real_sample_submission[chromium-grievance-APPROVE]";
            case "document" -> "::test_real_sample_submission[chromium-document-APPROVE]";
            case "loan-validation" -> "::test_loan_validation";
            case "applicant-not-found" -> "::test_applicant_not_found";
            default -> throw new IllegalArgumentException("Unknown browser demo scenario: " + scenario);
        };
        var command = new ArrayList<>(List.of(python, "-m", "pytest",
                "src/test/ui/browser/test_backend_journeys.py" + selector, "--browser", "chromium",
                "--tracing", "retain-on-failure", "--screenshot", "only-on-failure",
                "--output", "browser-results/backend",
                "--junitxml=browser-results/backend/results.xml", "-v"));
        if ("true".equals(System.getenv("BROWSER_DEMO_HEADED"))) command.add("--headed");
        var builder = new ProcessBuilder(command);
        builder.directory(Path.of(System.getProperty("user.dir")).toFile());
        builder.environment().put("BROWSER_TEST_BASE_URL", "http://127.0.0.1:" + port);
        builder.inheritIO();
        var process = builder.start();
        try {
            assertTrue(process.waitFor(15, TimeUnit.MINUTES), "Browser journeys exceeded fifteen minutes");
            assertEquals(0, process.exitValue(), "Browser journeys failed; inspect Playwright artifacts");
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroy);
                process.destroyForcibly();
            }
        }
    }
}
