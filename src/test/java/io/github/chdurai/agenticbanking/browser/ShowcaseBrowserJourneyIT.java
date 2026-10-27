package io.github.chdurai.agenticbanking.browser;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Opt-in release gate against the actual loopback-only no-key showcase. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "platform.datasource.url=jdbc:h2:mem:showcase-browser;DB_CLOSE_DELAY=-1",
        "platform.cases.poll-ms=100"
})
@ActiveProfiles("showcase")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ShowcaseBrowserJourneyIT {
    @LocalServerPort int port;

    @Test
    void realShowcaseJourneys() throws Exception {
        var python = System.getenv().getOrDefault("BROWSER_TEST_PYTHON", "python3");
        var processBuilder = new ProcessBuilder(List.of(python, "-m", "pytest",
                "src/test/ui/browser/test_showcase_journeys.py", "--browser", "chromium",
                "--tracing", "on", "--video", "on", "--screenshot", "on",
                "--output", "browser-results/showcase",
                "--junitxml=browser-results/showcase/results.xml", "-v"));
        processBuilder.environment().put("BROWSER_TEST_BASE_URL", "http://127.0.0.1:" + port);
        var process = processBuilder.inheritIO().start();
        try {
            assertTrue(process.waitFor(10, TimeUnit.MINUTES), "Showcase browser gate exceeded ten minutes");
            assertEquals(0, process.exitValue(), "Showcase browser gate failed; inspect retained artifacts");
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroy);
                process.destroyForcibly();
            }
        }
    }
}
