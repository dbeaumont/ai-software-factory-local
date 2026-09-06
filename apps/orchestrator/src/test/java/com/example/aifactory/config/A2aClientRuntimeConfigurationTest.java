package com.example.aifactory.config;

import com.example.aifactory.a2a.A2aContractMapping;
import com.example.aifactory.service.AgentCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class A2aClientRuntimeConfigurationTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void loadsOnlyPinnedSha256CardKeys() throws Exception {
        Path trust = directory.resolve("card-trust.json");
        Files.writeString(trust, "{\"version\":\"1\",\"keys\":{\"kid-1\":\""
                + "a".repeat(64) + "\"}}");

        assertThat(A2aClientRuntimeConfiguration.trustedFingerprints(trust.toString(), mapper))
                .hasSize(1).containsEntry("kid-1", "a".repeat(64));

        Files.writeString(trust, "{\"version\":\"1\",\"keys\":{\"kid-1\":\"untrusted\"}}");
        assertThatThrownBy(() -> A2aClientRuntimeConfiguration.trustedFingerprints(
                trust.toString(), mapper)).isInstanceOf(SecurityException.class);
    }

    @Test
    void exposesEveryCatalogAgentSkillToCardVerification() {
        A2aContractMapping mapping = new A2aContractMapping(mapper);
        AgentCatalog catalog = new AgentCatalog();

        catalog.roles().values().stream().filter(role ->
                        "agent".equals(role.kind()) || "sub-agent".equals(role.kind())).forEach(role ->
                assertThat(mapping.inputSkills(role.name())).as(role.name()).isNotEmpty()
                        .allMatch(skill -> skill.startsWith(role.name() + ".")));
    }
}
