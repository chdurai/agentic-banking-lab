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

/**
 * Visible Mission Control demos against an isolated application with the richest agentic configuration:
 * DAG runtime (agents light up concurrently), typed state, and the bounded challenge protocol v2 so
 * agent-to-agent CHALLENGE traffic appears in the collaboration lane. The model stays deterministic.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.ai.model.chat=none", "spring.ai.model.embedding=none",
        "spring.ai.mcp.client.enabled=false", "spring.ai.mcp.server.enabled=false",
        "agents.anomaly.enabled=false", "platform.persistence=memory",
        "platform.callers.required=false", "platform.hitl.auth=dev",
        "platform.hitl.enforce-roles=false", "platform.hitl.blind-first-sample-rate=1.0",
        "platform.cases.worker-enabled=true", "platform.cases.poll-ms=100",
        "agents.rag-mode=keyword",
        "platform.flow.state=v2", "platform.flow.runtime=dag", "platform.flow.challenge=v2"
})
@Import(MissionControlJourneyIT.ModelConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MissionControlJourneyIT {
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
    void missionControlJourneysAgainstRealApplication() throws Exception {
        var python = System.getenv().getOrDefault("BROWSER_TEST_PYTHON", "python3");
        var scenario = System.getenv().getOrDefault("BROWSER_DEMO_SCENARIO", "mission-control");
        var selector = switch (scenario) {
            case "all" -> "";
            case "mission-control" -> "::test_demo_launch_lights_the_constellation";
            case "mc-swarm" -> "::test_workbench_loan_watched_from_mission_control";
            case "mc-multi-flow" -> "::test_multi_flow_swarm";
            default -> throw new IllegalArgumentException("Unknown mission-control demo scenario: " + scenario);
        };
        var command = new ArrayList<>(List.of(python, "-m", "pytest",
                "src/test/ui/browser/test_mission_control.py" + selector, "--browser", "chromium",
                "--tracing", "retain-on-failure", "--screenshot", "only-on-failure",
                "--output", "browser-results/mission-control",
                "--junitxml=browser-results/mission-control/results.xml", "-v"));
        if ("true".equals(System.getenv("BROWSER_DEMO_HEADED"))) command.add("--headed");
        var builder = new ProcessBuilder(command);
        builder.directory(Path.of(System.getProperty("user.dir")).toFile());
        builder.environment().put("BROWSER_TEST_BASE_URL", "http://127.0.0.1:" + port);
        builder.inheritIO();
        var process = builder.start();
        try {
            assertTrue(process.waitFor(15, TimeUnit.MINUTES), "Mission Control journeys exceeded fifteen minutes");
            assertEquals(0, process.exitValue(), "Mission Control journeys failed; inspect Playwright artifacts");
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroy);
                process.destroyForcibly();
            }
        }
    }
}
