package com.example.aifactory.service;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityAgentsTest {
    private static final Path RESOURCES = Path.of(System.getProperty("user.dir"))
            .resolve("../../resources").normalize();

    @Test
    @SuppressWarnings("unchecked")
    void createsThreeDistinctSecurityRolesWithPromptsAndHostSelectedCapabilities() throws Exception {
        RecordingExecutor runtime = new RecordingExecutor();
        AgentCatalog catalog = new AgentCatalog();
        SecurityAgents agents = agents(runtime);

        for (String role : List.of("security-agent", "threat-model", "security-findings")) {
            agents.execute(request(role));
            Map<String, Object> manifest = new Yaml(new SafeConstructor(new LoaderOptions())).load(
                    Files.readString(RESOURCES.resolve("agents/" + role + ".yaml")));
            assertThat(manifest).containsEntry("role", role).containsEntry("effectful", false)
                    .containsEntry("parent", catalog.require(role).parent());
            assertThat((List<String>) manifest.get("allowed_tools"))
                    .containsExactlyElementsOf(catalog.require(role).tools());
            assertThat((List<String>) manifest.get("output_contracts"))
                    .containsExactly("security-assessment-v1");
            assertThat(Files.readString(RESOURCES.resolve(manifest.get("prompt").toString())))
                    .startsWith("# ").contains("security-assessment-v1");
        }
        assertThat(runtime.invocations).extracting(AgentRuntime.Invocation::role)
                .containsExactly("security-agent", "threat-model", "security-findings");
    }

    @Test
    void rejectsRolesOutsideSecurityBeforeRuntime() {
        RecordingExecutor runtime = new RecordingExecutor();
        SecurityAgents agents = agents(runtime);
        assertThatThrownBy(() -> agents.execute(request("developer"))).hasMessageContaining("outside");
        assertThat(runtime.invocations).isEmpty();
    }

    @Test
    void threatModelIsLimitedToContextAndDependencyReads() {
        RecordingExecutor runtime = new RecordingExecutor();
        AgentCatalog catalog = new AgentCatalog();
        agents(runtime).execute(request("threat-model"));

        AgentRuntime.Invocation invocation = runtime.invocations.getFirst();
        assertThat(invocation.allowedTools()).containsExactlyInAnyOrder(
                "context.search_code", "context.read_file", "context.get_dependencies", "context.get_symbols");
        assertThat(invocation.allowedTools()).noneMatch(tool -> tool.startsWith("evidence.")
                || tool.startsWith("sandbox.") || tool.startsWith("scm.") || tool.startsWith("assurance."));
        assertThat(invocation.allowedTools()).containsExactlyInAnyOrderElementsOf(
                catalog.require("threat-model").tools());
    }

    private static SecurityAgents.Request request(String role) {
        return new SecurityAgents.Request("task-1", "attempt-1", "a".repeat(40), role,
                Set.of("security-1"), "untrusted input",
                new AgentToolLoop.Budget(3, Duration.ofMinutes(2), 3000, 1000000),
                normalizedFindings(), Set.of(new SecurityFindingsInputValidator.EvidenceReference(
                "evidence://task-1/security", "b".repeat(64))), Set.of());
    }

    private static SecurityAgents agents(RecordingExecutor runtime) {
        return new SecurityAgents(runtime, new AgentCatalog(),
                new SecurityFindingsInputValidator(new ObjectMapper()), new SecurityDecisionValidator());
    }

    private static String normalizedFindings() {
        return """
                {"schema_version":"1","task_id":"task-1","attempt_id":"attempt-1",
                 "source_commit":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","scanner":"trivy",
                 "verdict":"PASSED","findings":[],
                 "summary":{"unknown":0,"low":0,"medium":0,"high":0,"critical":0},
                 "evidence":{"uri":"evidence://task-1/security",
                 "digest":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                 "status":"COMPLETE"}}
                """;
    }

    private static final class RecordingExecutor implements AgentExecutor {
        private final List<AgentRuntime.Invocation> invocations = new ArrayList<>();

        @Override public AgentRuntime.Result execute(AgentRuntime.Invocation invocation) {
            invocations.add(invocation);
            try {
                var document = new ObjectMapper().readTree(Files.readString(
                        RESOURCES.resolve("multiagents/fixtures/golden-contracts-v1.json")))
                        .path("documents").path("security-assessment-v1");
                return new AgentRuntime.Result(document, "f".repeat(64), 1, 10, 1);
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }
    }
}
