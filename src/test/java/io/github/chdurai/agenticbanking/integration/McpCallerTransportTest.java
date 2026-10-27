package io.github.chdurai.agenticbanking.integration;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.ai.model.chat=none", "spring.ai.model.embedding=none",
        "spring.ai.mcp.client.enabled=false", "spring.ai.mcp.server.enabled=true",
        "agents.anomaly.enabled=false", "platform.callers.required=true",
        "platform.callers.credentials.reader.secret=mcp-reader-test-only-credential",
        "platform.callers.credentials.reader.tenant-id=alpha",
        "platform.callers.credentials.reader.actor=reader",
        "platform.callers.credentials.reader.roles[0]=KNOWLEDGE_READER",
        "platform.callers.credentials.other.secret=mcp-other-test-only-credential",
        "platform.callers.credentials.other.tenant-id=beta",
        "platform.callers.credentials.other.actor=other",
        "platform.callers.credentials.other.roles[0]=KNOWLEDGE_READER",
        "platform.llm.providers.approved[0]=nvidia-nim",
        "platform.llm.governance.region=test",
        "platform.llm.governance.no-training=true",
        "platform.llm.governance.retention-reviewed=true",
        "platform.llm.governance.terms-reference=stub-only"
})
@Import(AgentFlowsStubbedTest.StubConfig.class)
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class McpCallerTransportTest {
    @LocalServerPort int port;
    private String url() { return "http://localhost:" + port; }

    @Test void actualSseToolCallbackRetainsAuthenticatedRoleAcrossReactorThreads() {
        var transport = HttpClientSseClientTransport.builder(url())
                .customizeRequest(r -> r.header("Authorization", "Bearer mcp-reader-test-only-credential")).build();
        try (var client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(10)).build()) {
            client.initialize();
            assertThat(client.listTools().tools()).hasSize(4);
            var result = client.callTool(new McpSchema.CallToolRequest("searchKnowledge",
                    Map.of("query", "debt to income lending", "topK", 2)));
            assertThat(result.isError()).isNotEqualTo(true);
            assertThat(result.content().toString()).contains("policies");
            var denied = client.callTool(new McpSchema.CallToolRequest("getCostReport", Map.of()));
            assertThat(denied.isError()).isTrue();
        }
    }

    @Test void sseSessionCannotBeUsedByAnotherAuthenticatedTenant() throws Exception {
        try (var http = HttpClient.newHttpClient()) {
            var response = http.send(HttpRequest.newBuilder(URI.create(url() + "/sse"))
                    .header("Authorization", "Bearer mcp-reader-test-only-credential")
                    .header("Accept", "text/event-stream").GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            assertThat(response.statusCode()).isEqualTo(200);
            try (var reader = new BufferedReader(new InputStreamReader(response.body()))) {
                String endpoint = null;
                for (int i = 0; i < 10 && endpoint == null; i++) {
                    String line = reader.readLine();
                    if (line != null && line.startsWith("data:")) endpoint = line.substring(5).trim();
                }
                assertThat(endpoint).contains("sessionId=");
                var denied = http.send(HttpRequest.newBuilder(URI.create(url() + endpoint))
                        .header("Authorization", "Bearer mcp-other-test-only-credential")
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"))
                        .build(), HttpResponse.BodyHandlers.ofString());
                assertThat(denied.statusCode()).isEqualTo(403);
            }
        }
    }
}
