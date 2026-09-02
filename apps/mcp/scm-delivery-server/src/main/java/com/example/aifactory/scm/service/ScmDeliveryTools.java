package com.example.aifactory.scm.service;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

@Service
public class ScmDeliveryTools {
    private final RepositoryRegistry repositories;
    private final ScmRepositoryClient client;
    private final ScmDeliveryService delivery;

    public ScmDeliveryTools(RepositoryRegistry repositories, ScmRepositoryClient client, ScmDeliveryService delivery) {
        this.repositories = repositories;
        this.client = client;
        this.delivery = delivery;
    }

    @Tool(name = "scm.create_draft_pull_request", description = "Atomically commit, push and create one draft PR after approval verification")
    public ScmDeliveryBackend.DeliveryResult createDraftPullRequest(
            @ToolParam(description = "Contract version, currently 1") String schema_version,
            @ToolParam(description = "Registered task identifier") String task_id,
            @ToolParam(description = "Stable workflow attempt identifier") String attempt_id,
            @ToolParam(description = "Server-registered repository identifier") String repository_id,
            @ToolParam(description = "Immutable source commit SHA") String source_commit,
            @ToolParam(description = "SHA-256 of changes.patch") String patch_digest,
            @ToolParam(description = "SHA-256 digests keyed by plan, tests, quality, sbom, security and review") Map<String, String> evidence_digests,
            @ToolParam(description = "Allow-listed base branch") String base_branch,
            @ToolParam(description = "Human-readable PR title") String title,
            @ToolParam(description = "Authorized deterministic workflow caller") String actor,
            @ToolParam(description = "Stable command idempotency key") String idempotency_key,
            @ToolParam(description = "Signed approval proof bound to this delivery") ApprovalProof approval_proof) throws Exception {
        return delivery.create(new ScmDeliveryService.CreateRequest(schema_version, task_id, attempt_id, repository_id,
                source_commit, patch_digest, evidence_digests, base_branch, title, actor, idempotency_key, approval_proof));
    }

    @Tool(name = "scm.get_repository", description = "Return secret-free metadata for an allow-listed repository_id")
    public ScmRepositoryClient.RepositoryMetadata getRepository(
            @ToolParam(description = "Contract version, currently 1") String schema_version,
            @ToolParam(description = "Server-registered repository identifier") String repository_id,
            @ToolParam(description = "Authorized caller: workflow or delivery") String actor,
            @ToolParam(description = "RFC 3339 deadline") String deadline) {
        validateRequest(schema_version, actor, deadline);
        return client.getRepository(repositories.require(repository_id));
    }

    @Tool(name = "scm.resolve_revision", description = "Resolve an allow-listed branch to an immutable source commit")
    public ScmRepositoryClient.Revision resolveRevision(
            @ToolParam(description = "Contract version, currently 1") String schema_version,
            @ToolParam(description = "Server-registered repository identifier") String repository_id,
            @ToolParam(description = "Allow-listed base branch") String base_branch,
            @ToolParam(description = "Authorized caller: workflow or delivery") String actor,
            @ToolParam(description = "RFC 3339 deadline") String deadline) {
        validateRequest(schema_version, actor, deadline);
        RepositoryRegistry.RepositoryDefinition repository = repositories.require(repository_id);
        repository.requireBaseBranch(base_branch);
        return client.resolveRevision(repository, base_branch);
    }

    private static void validateRequest(String schemaVersion, String actor, String deadline) {
        if (!"1".equals(schemaVersion)) {
            throw new IllegalArgumentException("unsupported schema_version");
        }
        if (!"workflow".equals(actor) && !"delivery".equals(actor)) {
            throw new SecurityException("actor is not authorized for SCM metadata");
        }
        Instant parsed = Instant.parse(deadline);
        if (!parsed.isAfter(Instant.now())) {
            throw new IllegalArgumentException("deadline has expired");
        }
    }
}
