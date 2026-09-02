package com.example.aifactory.service;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SupervisorManifestTest {
    private static final Path RESOURCES = Path.of(System.getProperty("user.dir"))
            .resolve("../../resources").normalize();

    @Test
    @SuppressWarnings("unchecked")
    void manifestAndPromptAreVersionedAndConsistentWithTheHostCatalog() throws Exception {
        Map<String, Object> manifest = new Yaml(new SafeConstructor(new LoaderOptions())).load(
                Files.readString(RESOURCES.resolve("agents/supervisor.yaml")));
        AgentCatalog.Role catalogRole = new AgentCatalog().require("supervisor");

        assertThat(manifest).containsEntry("schema_version", "1")
                .containsEntry("manifest_id", "supervisor-v1")
                .containsEntry("role", "supervisor")
                .containsEntry("version", "1.0.0")
                .containsEntry("owner", catalogRole.owner())
                .containsEntry("parent", catalogRole.parent())
                .containsEntry("autonomy", catalogRole.autonomy())
                .containsEntry("effectful", false)
                .containsEntry("primary_output_contract", catalogRole.outputContract());
        assertThat((List<String>) manifest.get("may_delegate_to"))
                .containsExactlyElementsOf(catalogRole.mayDelegateTo());
        assertThat((List<String>) manifest.get("allowed_tools"))
                .containsExactlyElementsOf(catalogRole.tools());
        assertThat((List<String>) manifest.get("output_contracts"))
                .containsExactly("delegation-plan-v1", "supervisor-decision-v1");
        assertThat((Map<String, Object>) manifest.get("default_budget"))
                .containsKeys("max_turns", "max_tokens", "max_cost_micros", "timeout_seconds", "max_tool_calls");

        String promptPath = manifest.get("prompt").toString();
        String prompt = Files.readString(RESOURCES.resolve(promptPath));
        assertThat(prompt).contains("# Supervisor v1")
                .contains("`DECOMPOSE`", "`CONSOLIDATE`", "`REPLAN`")
                .contains("`delegation-plan-v1`", "`supervisor-decision-v1`")
                .contains("Retourne uniquement un objet JSON");
    }
}
