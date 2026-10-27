package io.github.chdurai.agenticbanking.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CapacityArtifactsTest {
    private final ObjectMapper json = new ObjectMapper();
    private final Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));

    @Test void scalingExampleIsPausedSingleReplicaAndAuthenticatedWithFailClosedQueries() throws Exception {
        var documents = new ArrayList<JsonNode>();
        yaml.loadAll(Files.readString(Path.of("deploy/observability/keda-scaledobject.yml")))
                .forEach(document -> documents.add(json.valueToTree(document)));
        assertThat(documents).hasSize(2);
        assertThat(documents.getFirst().path("kind").asText()).isEqualTo("TriggerAuthentication");
        assertThat(documents.getFirst().at("/spec/secretTargetRef/0/parameter").asText()).isEqualTo("bearerToken");
        JsonNode scaler = documents.getLast();
        assertThat(scaler.path("kind").asText()).isEqualTo("ScaledObject");
        assertThat(scaler.at("/metadata/annotations/autoscaling.keda.sh~1paused").asText()).isEqualTo("true");
        assertThat(scaler.at("/spec/minReplicaCount").asInt()).isEqualTo(1);
        assertThat(scaler.at("/spec/maxReplicaCount").asInt()).isEqualTo(1);
        assertThat(scaler.at("/spec/triggers")).hasSize(2);
        for (JsonNode trigger : scaler.at("/spec/triggers")) {
            assertThat(trigger.at("/metadata/serverAddress").asText()).startsWith("https://");
            assertThat(trigger.at("/metadata/authModes").asText()).isEqualTo("bearer");
            assertThat(trigger.at("/metadata/ignoreNullValues").asText()).isEqualTo("false");
            assertThat(trigger.at("/authenticationRef/name").asText()).isEqualTo("agentic-banking-prometheus");
        }
        assertThat(scaler.at("/spec/triggers/0/metadata/query").asText()).startsWith("max(case_queue_depth");
        assertThat(scaler.at("/spec/triggers/1/metadata/query").asText()).startsWith("sum(llm_calls_inflight");
    }

    @Test void dashboardsAndAlertsAreParseableAndIncludeLatencySpendAndTelemetryGaps() throws Exception {
        JsonNode dashboard = json.readTree(Path.of("deploy/observability/grafana-dashboard.json").toFile());
        assertThat(dashboard.path("panels")).hasSize(8);
        for (JsonNode panel : dashboard.path("panels")) {
            assertThat(panel.at("/datasource/uid").asText()).isEqualTo("$datasource");
            for (JsonNode target : panel.path("targets")) {
                assertThat(target.path("expr").asText()).contains("job=\"agentic-banking\"");
            }
        }
        JsonNode rules = json.valueToTree(yaml.load(Files.readString(Path.of("deploy/observability/prometheus-rules.yml"))));
        List<String> names = new ArrayList<>();
        rules.at("/groups/0/rules").forEach(rule -> {
            assertThat(rule.path("expr").asText()).isNotBlank();
            if (rule.has("alert")) names.add(rule.path("alert").asText());
        });
        assertThat(names).contains("CaseLatencyTargetMissed", "EstimatedMonthlyModelSpend", "IncompleteModelSpendTelemetry");
    }
}
