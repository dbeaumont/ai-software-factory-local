package com.example.aifactory.a2a;

import com.example.aifactory.service.MultiAgentContractValidator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;

import org.erdtman.jcs.JsonCanonicalizer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class A2aBusinessContractGuardTest {
    private static final Path FIXTURES = Path.of(System.getProperty(
            "multiagent.fixtures.directory", "../../resources/multiagents/fixtures"));
    private final ObjectMapper mapper = new ObjectMapper();
    private final A2aBusinessContractGuard guard = new A2aBusinessContractGuard(
            new A2aContractMapping(mapper), new MultiAgentContractValidator(mapper));
    private final MultiAgentContractValidator.ContractContext context =
            new MultiAgentContractValidator.ContractContext("task-1", "attempt-1",
                    Set.of("plan-1", "node-1", "assessment-1", "code-1"));

    @Test
    void validatesBusinessJsonBeforeA2aSendAndAfterArtifactReceipt() throws Exception {
        JsonNode fixtures = fixtures();
        assertThat(guard.beforeSend("developer", "code-task-v1", fixtures.path("code-task-v1"), context))
                .isSameAs(fixtures.path("code-task-v1"));
        assertThat(guard.afterReceive("developer", "patch-proposal-v1",
                fixtures.path("patch-proposal-v1"), context)).isSameAs(fixtures.path("patch-proposal-v1"));
    }

    @Test
    void protocolShapeCannotBypassBusinessSchemaOrRoleMapping() throws Exception {
        JsonNode fixtures = fixtures();
        assertThatThrownBy(() -> guard.beforeSend("developer", "code-task-v1", mapper.readTree("{}"), context))
                .isInstanceOf(MultiAgentContractValidator.ContractValidationException.class);
        assertThatThrownBy(() -> guard.beforeSend(
                "patch-repair", "code-task-v1", fixtures.path("code-task-v1"), context))
                .hasMessageContaining("not an A2A input");
        assertThatThrownBy(() -> guard.afterReceive(
                "developer", "security-assessment-v1", fixtures.path("security-assessment-v1"), context))
                .hasMessageContaining("not the primary A2A output");
    }

    @Test
    void preservesCanonicalDigestsAndUsageAcrossEveryPrimaryA2aOutput() throws Exception {
        JsonNode fixtures = fixtures();
        JsonNode mapping;
        try (var input = getClass().getClassLoader().getResourceAsStream("a2a/skill-contract-map-v1.json")) {
            mapping = mapper.readTree(input);
        }
        Set<String> references = new HashSet<>();
        collectText(fixtures, references);
        MultiAgentContractValidator.ContractContext broadContext =
                new MultiAgentContractValidator.ContractContext("task-1", "attempt-1", references);
        int validated = 0;
        for (JsonNode output : mapping.path("outputs")) {
            if (!output.path("primary_artifact").asBoolean()) continue;
            String role = output.path("role").asText();
            String contract = output.path("output_contract").asText();
            JsonNode golden = fixtures.path(contract);
            JsonNode usageBefore = golden.path("usage").deepCopy();
            String digestBefore = digest(golden);

            JsonNode received = guard.afterReceive(role, contract, golden, broadContext);

            assertThat(digest(received)).as(role + ":" + contract).isEqualTo(digestBefore);
            assertThat(received.path("usage")).isEqualTo(usageBefore);
            validated++;
        }
        assertThat(validated).isEqualTo(14);
    }

    private String digest(JsonNode document) throws Exception {
        byte[] canonical = new JsonCanonicalizer(mapper.writeValueAsBytes(document)).getEncodedUTF8();
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
    }

    private static void collectText(JsonNode node, Set<String> values) {
        if (node.isTextual()) {
            values.add(node.asText());
        } else {
            node.forEach(value -> collectText(value, values));
        }
    }

    private JsonNode fixtures() throws Exception {
        return mapper.readTree(Files.readString(FIXTURES.resolve("golden-contracts-v1.json"))).path("documents");
    }
}
