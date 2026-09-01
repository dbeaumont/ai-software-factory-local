package com.example.aifactory.scm.service;

import com.example.aifactory.scm.config.ScmDeliveryProperties;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.JsonNode;

import java.time.Duration;

@Service
public class GiteaRepositoryClient implements ScmRepositoryClient {
    private final ScmDeliveryProperties properties;
    private final ScmCredentials credentials;
    private final WebClient client;

    public GiteaRepositoryClient(ScmDeliveryProperties properties, ScmCredentials credentials, WebClient.Builder builder) {
        this.properties = properties;
        this.credentials = credentials;
        this.client = builder.baseUrl(properties.giteaBaseUrl()).build();
    }

    @Override
    public RepositoryMetadata getRepository(RepositoryRegistry.RepositoryDefinition repository) {
        JsonNode response = client.get()
                .uri("/api/v1/repos/{owner}/{name}", repository.owner(), repository.name())
                .header("Authorization", "token " + credentials.giteaToken())
                .retrieve().bodyToMono(JsonNode.class).block(Duration.ofSeconds(20));
        if (response == null || response.path("id").isMissingNode()) {
            throw new IllegalStateException("SCM repository response is incomplete");
        }
        return new RepositoryMetadata(repository.repositoryId(), repository.owner(), repository.name(),
                response.path("default_branch").asText(), response.path("archived").asBoolean(false),
                response.path("empty").asBoolean(false));
    }

    @Override
    public Revision resolveRevision(RepositoryRegistry.RepositoryDefinition repository, String branch) {
        repository.requireBaseBranch(branch);
        JsonNode response = client.get()
                .uri(builder -> builder.path("/api/v1/repos/{owner}/{name}/branches/{branch}")
                        .build(repository.owner(), repository.name(), branch))
                .header("Authorization", "token " + credentials.giteaToken())
                .retrieve().bodyToMono(JsonNode.class).block(Duration.ofSeconds(20));
        String sha = response == null ? "" : response.path("commit").path("id").asText();
        if (!sha.matches("[0-9a-f]{40}")) {
            throw new IllegalStateException("SCM revision response has no immutable SHA");
        }
        return new Revision(repository.repositoryId(), branch, sha);
    }

}
