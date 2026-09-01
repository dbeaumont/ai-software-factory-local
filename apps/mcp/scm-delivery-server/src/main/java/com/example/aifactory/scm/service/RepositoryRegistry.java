package com.example.aifactory.scm.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.example.aifactory.scm.config.ScmDeliveryProperties;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class RepositoryRegistry {
    private final Map<String, RepositoryDefinition> repositories;

    public RepositoryRegistry(ScmDeliveryProperties properties, ObjectMapper mapper) throws Exception {
        RegistryDocument document = mapper.readValue(Files.readString(properties.repositoryRegistry()), RegistryDocument.class);
        this.repositories = document.repositories().stream().collect(Collectors.toUnmodifiableMap(
                RepositoryDefinition::repositoryId, Function.identity()));
    }

    public RepositoryDefinition require(String repositoryId) {
        RepositoryDefinition repository = repositories.get(repositoryId);
        if (repository == null) {
            throw new IllegalArgumentException("repository_id is not registered");
        }
        return repository;
    }

    public record RegistryDocument(@JsonProperty("schema_version") String schemaVersion,
                                   List<RepositoryDefinition> repositories) {
        public RegistryDocument {
            if (!"1".equals(schemaVersion) || repositories == null || repositories.isEmpty()) {
                throw new IllegalArgumentException("invalid SCM repository registry");
            }
            repositories = List.copyOf(repositories);
        }
    }

    public record RepositoryDefinition(
            @JsonProperty("repository_id") String repositoryId,
            String owner,
            String name,
            @JsonProperty("clone_path") String clonePath,
            @JsonProperty("allowed_base_branches") List<String> allowedBaseBranches) {
        public RepositoryDefinition {
            if (repositoryId == null || !repositoryId.matches("[a-z0-9][a-z0-9-]{1,62}")
                    || owner == null || !owner.matches("[A-Za-z0-9_.-]+")
                    || name == null || !name.matches("[A-Za-z0-9_.-]+")
                    || clonePath == null || !clonePath.equals("/" + owner + "/" + name + ".git")
                    || allowedBaseBranches == null || allowedBaseBranches.isEmpty()
                    || allowedBaseBranches.stream().anyMatch(branch -> !branch.matches("[A-Za-z0-9._/-]+"))) {
                throw new IllegalArgumentException("invalid registered repository definition");
            }
            allowedBaseBranches = List.copyOf(allowedBaseBranches);
        }

        public void requireBaseBranch(String branch) {
            if (!allowedBaseBranches.contains(branch)) {
                throw new IllegalArgumentException("base branch is not allowed for repository_id");
            }
        }
    }
}
