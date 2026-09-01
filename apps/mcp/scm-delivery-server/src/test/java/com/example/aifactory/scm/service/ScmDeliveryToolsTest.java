package com.example.aifactory.scm.service;

import com.example.aifactory.scm.config.ScmDeliveryProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ScmDeliveryToolsTest {
    @Test
    void exposesOnlyRegisteredMetadataAndImmutableRevision(@TempDir Path root) throws Exception {
        RepositoryRegistry registry = registry(root);
        ScmRepositoryClient client = new ScmRepositoryClient() {
            @Override
            public RepositoryMetadata getRepository(RepositoryRegistry.RepositoryDefinition repository) {
                return new RepositoryMetadata(repository.repositoryId(), repository.owner(), repository.name(),
                        "main", false, false);
            }

            @Override
            public Revision resolveRevision(RepositoryRegistry.RepositoryDefinition repository, String branch) {
                return new Revision(repository.repositoryId(), branch, "a".repeat(40));
            }
        };
        ScmDeliveryTools tools = new ScmDeliveryTools(registry, client);
        String deadline = Instant.now().plusSeconds(60).toString();

        assertEquals("customer-api", tools.getRepository("1", "customer-api", "workflow", deadline).repositoryId());
        assertEquals("a".repeat(40), tools.resolveRevision("1", "customer-api", "main", "delivery", deadline)
                .sourceCommit());
        assertThrows(IllegalArgumentException.class,
                () -> tools.resolveRevision("1", "customer-api", "feature/unregistered", "workflow", deadline));
        assertThrows(SecurityException.class,
                () -> tools.getRepository("1", "customer-api", "planner", deadline));
    }

    private static RepositoryRegistry registry(Path root) throws Exception {
        Path file = root.resolve("registry.json");
        Files.writeString(file, """
                {"schema_version":"1","repositories":[{"repository_id":"customer-api","owner":"aiadmin",
                "name":"customer-api","clone_path":"/aiadmin/customer-api.git","allowed_base_branches":["main"]}]}
                """);
        return new RepositoryRegistry(new ScmDeliveryProperties("http://gitea:3000", "http://localhost:3000",
                "delivery", root.resolve("token"), root.resolve("state"), file), new ObjectMapper());
    }
}
