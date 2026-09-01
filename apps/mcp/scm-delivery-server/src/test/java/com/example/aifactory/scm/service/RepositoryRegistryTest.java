package com.example.aifactory.scm.service;

import com.example.aifactory.scm.config.ScmDeliveryProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RepositoryRegistryTest {
    @Test
    void resolvesOnlyRegisteredIdsAndBranches(@TempDir Path root) throws Exception {
        Path registry = root.resolve("repositories.json");
        Files.writeString(registry, """
                {"schema_version":"1","repositories":[{"repository_id":"customer-api","owner":"aiadmin",
                "name":"customer-api","clone_path":"/aiadmin/customer-api.git","allowed_base_branches":["main"]}]}
                """);
        RepositoryRegistry repositories = new RepositoryRegistry(properties(root, registry), new ObjectMapper());

        RepositoryRegistry.RepositoryDefinition repository = repositories.require("customer-api");
        assertEquals("/aiadmin/customer-api.git", repository.clonePath());
        repository.requireBaseBranch("main");
        assertThrows(IllegalArgumentException.class, () -> repositories.require("https://attacker.invalid/repo.git"));
        assertThrows(IllegalArgumentException.class, () -> repository.requireBaseBranch("untrusted"));
    }

    private static ScmDeliveryProperties properties(Path root, Path registry) {
        return new ScmDeliveryProperties("http://gitea:3000", "http://localhost:3000", "delivery",
                root.resolve("token"), root.resolve("state"), registry);
    }
}
