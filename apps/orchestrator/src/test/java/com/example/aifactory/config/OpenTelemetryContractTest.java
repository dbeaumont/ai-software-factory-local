package com.example.aifactory.config;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class OpenTelemetryContractTest {
    private static final Set<String> SERVICES = Set.of(
            "ai-factory-orchestrator", "repository-context-mcp", "sandbox-execution-mcp",
            "scm-delivery-mcp", "assurance-mcp", "evidence-mcp");
    private static final Set<String> SPANS = Set.of(
            "ai.task.execute", "ai.workflow.execute", "ai.agent.execute", "gen_ai.chat", "mcp.call",
            "sandbox.job", "assurance.evaluate", "scm.create_draft_pull_request");
    private static final Set<String> CORRELATION_IDS = Set.of(
            "ai.task.id", "ai.attempt.id", "ai.run.id", "ai.delegation.id", "ai.agent.run.id",
            "ai.sandbox.execution.id", "temporal.workflow.id", "temporal.run.id", "scm.pull_request.id");

    @Test
    void definesVersionedResourcesSpansAndMetrics() throws Exception {
        JsonNode contract = contract();

        assertThat(contract.path("schema_version").asText()).isEqualTo("1.0");
        assertThat(texts(contract.path("resource").path("required_attributes"))).containsExactlyInAnyOrder(
                "service.name", "service.namespace", "service.version", "deployment.environment.name");
        assertThat(texts(contract.path("resource").path("services"))).isEqualTo(SERVICES);
        assertThat(textsFromObjects(contract.path("spans"), "name")).isEqualTo(SPANS);
        assertThat(contract.path("metrics").size()).isGreaterThanOrEqualTo(30);
        assertThat(textsFromObjects(contract.path("metrics"), "name")).allMatch(name -> name.startsWith("ai."));
        assertThat(textsFromObjects(contract.path("metrics"), "name")).doesNotHaveDuplicates();
        assertThat(texts(contract.path("propagation").path("formats")))
                .containsExactlyInAnyOrder("tracecontext", "baggage");
        JsonNode bounded = contract.path("attribute_policy").path("bounded_values");
        assertThat(bounded.properties()).isNotEmpty();
        bounded.properties().forEach(entry -> {
            assertThat(entry.getValue().isArray()).as("bounded values for %s", entry.getKey()).isTrue();
            assertThat(texts(entry.getValue())).as("bounded values for %s", entry.getKey()).contains("unknown");
        });
    }

    @Test
    void keepsCorrelationIdentifiersOutOfMetricDimensions() throws Exception {
        JsonNode contract = contract();
        assertThat(texts(contract.path("attribute_policy").path("correlation_only")))
                .containsExactlyInAnyOrderElementsOf(CORRELATION_IDS);

        contract.path("metrics").forEach(metric ->
                assertThat(texts(metric.path("attributes")))
                        .as("bounded dimensions for %s", metric.path("name").asText())
                        .doesNotContainAnyElementsOf(CORRELATION_IDS));
    }

    @Test
    void disablesContentCaptureAndListsSensitiveAttributePatterns() throws Exception {
        JsonNode contract = contract();
        JsonNode capture = contract.path("content_capture");
        assertThat(capture.path("prompts").asBoolean()).isFalse();
        assertThat(capture.path("results").asBoolean()).isFalse();
        assertThat(capture.path("evidence").asBoolean()).isFalse();
        assertThat(capture.path("gen_ai_message_content").asBoolean()).isFalse();

        assertThat(texts(contract.path("attribute_policy").path("forbidden_name_fragments")))
                .contains("authorization", "cookie", "password", "secret", "token.value", "api_key",
                        "prompt.content", "response.content", "source.code", "patch.content", "evidence.content");
        assertThat(contract.path("limits").path("attribute_count").asInt()).isBetween(1, 128);
        assertThat(contract.path("limits").path("attribute_value_length").asInt()).isBetween(1, 4096);

        Set<String> forbidden = texts(contract.path("attribute_policy").path("forbidden_name_fragments"));
        contract.path("metrics").forEach(metric -> texts(metric.path("attributes")).forEach(attribute ->
                assertThat(forbidden).noneMatch(attribute::contains)));
        contract.path("spans").forEach(span -> texts(span.path("low_cardinality_attributes"))
                .forEach(attribute -> assertThat(forbidden).noneMatch(attribute::contains)));
    }

    private static JsonNode contract() throws Exception {
        Path path = repositoryRoot().resolve("resources/observability/telemetry-contract-v1.json");
        return new ObjectMapper().readTree(Files.readString(path));
    }

    private static Set<String> texts(JsonNode array) {
        Set<String> values = new HashSet<>();
        array.forEach(value -> values.add(value.asText()));
        return values;
    }

    private static Set<String> textsFromObjects(JsonNode array, String field) {
        Set<String> values = new HashSet<>();
        array.forEach(value -> values.add(value.path(field).asText()));
        return values;
    }

    private static Path repositoryRoot() {
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        if (Files.isRegularFile(cwd.resolve("infrastructure/compose.yaml"))) return cwd;
        return cwd.resolve("../..").normalize();
    }
}
