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
import java.util.Arrays;
import java.util.Set;
import org.springframework.ai.tool.annotation.Tool;

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
        ScmDeliveryTools tools = new ScmDeliveryTools(registry, client, null);
        String deadline = Instant.now().plusSeconds(60).toString();

        assertEquals("customer-api", tools.getRepository("1", "customer-api", "workflow", deadline).repositoryId());
        assertEquals("a".repeat(40), tools.resolveRevision("1", "customer-api", "main", "delivery", deadline)
                .sourceCommit());
        assertThrows(IllegalArgumentException.class,
                () -> tools.resolveRevision("1", "customer-api", "feature/unregistered", "workflow", deadline));
        assertThrows(SecurityException.class,
                () -> tools.getRepository("1", "customer-api", "planner", deadline));
    }

    @Test
    void publishesNoMergeForcePushDeleteOrGenericGitTool() {
        Set<String> names = Arrays.stream(ScmDeliveryTools.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(Tool.class))
                .filter(java.util.Objects::nonNull)
                .map(Tool::name)
                .collect(java.util.stream.Collectors.toSet());

        assertEquals(Set.of("scm.get_repository", "scm.resolve_revision", "scm.create_draft_pull_request"), names);
    }

    private static RepositoryRegistry registry(Path root) throws Exception {
        Path file = root.resolve("registry.json");
        Files.writeString(file, """
                {"schema_version":"1","repositories":[{"repository_id":"customer-api","owner":"aiadmin",
                "name":"customer-api","clone_path":"/aiadmin/customer-api.git","allowed_base_branches":["main"]}]}
                """);
        return new RepositoryRegistry(new ScmDeliveryProperties("http://gitea:3000", "http://localhost:3000",
                "delivery", root.resolve("token"), root.resolve("state"), file, root.resolve("workspace"),
                root.resolve("approval-key")), new ObjectMapper());
    }
}
