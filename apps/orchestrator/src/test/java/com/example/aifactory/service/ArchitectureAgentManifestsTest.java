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

class ArchitectureAgentManifestsTest {
    private static final Path RESOURCES = Path.of(System.getProperty("user.dir"))
            .resolve("../../resources").normalize();

    @Test
    @SuppressWarnings("unchecked")
    void architectureRolesHaveVersionedManifestsPromptsAndCatalogBindings() throws Exception {
        AgentCatalog catalog = new AgentCatalog();
        Map<String, String> expectedContracts = Map.of(
                "architecture-agent", "architecture-assessment-v1",
                "impact-analysis", "specialist-result-v1",
                "dependencies-contracts", "specialist-result-v1");

        for (Map.Entry<String, String> expected : expectedContracts.entrySet()) {
            String roleName = expected.getKey();
            Map<String, Object> manifest = manifest(roleName);
            AgentCatalog.Role role = catalog.require(roleName);
            assertThat(manifest).containsEntry("schema_version", "1")
                    .containsEntry("manifest_id", roleName + "-v1")
                    .containsEntry("role", roleName)
                    .containsEntry("version", "1.0.0")
                    .containsEntry("owner", role.owner())
                    .containsEntry("parent", role.parent())
                    .containsEntry("kind", role.kind())
                    .containsEntry("autonomy", role.autonomy())
                    .containsEntry("effectful", false);
            assertThat((List<String>) manifest.get("may_delegate_to"))
                    .containsExactlyElementsOf(role.mayDelegateTo());
            assertThat((List<String>) manifest.get("allowed_tools"))
                    .containsExactlyElementsOf(role.tools());
            assertThat((List<String>) manifest.get("output_contracts")).containsExactly(expected.getValue());
            String prompt = Files.readString(RESOURCES.resolve(manifest.get("prompt").toString()));
            assertThat(prompt).contains("# ").contains(expected.getValue()).contains("uniquement un objet JSON");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> manifest(String role) throws Exception {
        return new Yaml(new SafeConstructor(new LoaderOptions())).load(
                Files.readString(RESOURCES.resolve("agents/" + role + ".yaml")));
    }
}
