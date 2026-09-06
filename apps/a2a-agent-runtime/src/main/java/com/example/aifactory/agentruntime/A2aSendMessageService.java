package com.example.aifactory.agentruntime;

import org.erdtman.jcs.JsonCanonicalizer;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Validates and deduplicates asynchronous message/send submissions before durable execution starts. */
@Service
public final class A2aSendMessageService {
    static final String EXECUTION_CONTEXT_EXTENSION =
            "https://ai-factory.local/extensions/execution-context/v1";
    private static final int MAX_PARTS = 16;

    private final String activeRole;
    private final Set<String> allowedSkills;
    private final ObjectMapper mapper;
    private final Map<String, RegisteredSubmission> byMessageId = new ConcurrentHashMap<>();

    public A2aSendMessageService(AgentRuntimeProperties runtime, AgentCardCatalogGenerator cards,
                                 ObjectMapper mapper) {
        this.activeRole = runtime.role();
        AgentCardCatalogGenerator.GeneratedAgentCard card = cards.generate().get(activeRole);
        if (card == null) throw new IllegalStateException("No Agent Card source for active role");
        this.allowedSkills = card.skills().stream()
                .map(AgentCardCatalogGenerator.GeneratedSkill::id).collect(java.util.stream.Collectors.toUnmodifiableSet());
        this.mapper = mapper;
    }

    public Submission send(JsonNode params, Caller caller) {
        if (caller == null || caller.subject() == null || caller.subject().isBlank()) {
            throw new SubmissionRejected("Unauthenticated A2A caller");
        }
        JsonNode message = requiredObject(params, "message");
        String messageId = requiredText(message, "messageId");
        JsonNode parts = message.path("parts");
        if (!parts.isArray() || parts.isEmpty() || parts.size() > MAX_PARTS) {
            throw new SubmissionRejected("A2A message parts must contain between 1 and " + MAX_PARTS + " items");
        }
        JsonNode envelope = requiredObject(parts.get(0), "data");
        String role = requiredText(envelope, "target_role");
        String skill = requiredText(envelope, "skill_id");
        if (!activeRole.equals(role) || !allowedSkills.contains(skill)) {
            throw new SubmissionRejected("Role or skill is not admitted by this runtime");
        }
        requireScopes(caller.scopes(), Set.of("a2a.invoke", "a2a.role." + role, "a2a.skill." + skill));
        JsonNode metadata = requiredObject(message, "metadata");
        JsonNode execution = requiredObject(metadata, EXECUTION_CONTEXT_EXTENSION);
        validateExecutionContext(execution, role);
        if (params.path("configuration").path("blocking").asBoolean(false)) {
            throw new SubmissionRejected("Blocking message/send is disabled");
        }

        String digest = digest(message);
        synchronized (byMessageId) {
            RegisteredSubmission existing = byMessageId.get(messageId);
            if (existing != null) {
                if (!existing.messageDigest().equals(digest)) {
                    throw new SubmissionRejected("messageId collision with a different payload");
                }
                return existing.submission();
            }
            Submission created = new Submission(
                    UUID.randomUUID().toString(), UUID.randomUUID().toString(), messageId,
                    role, skill, caller.subject(), requiredText(execution, "delegationId"), Instant.now());
            byMessageId.put(messageId, new RegisteredSubmission(digest, created));
            return created;
        }
    }

    private void validateExecutionContext(JsonNode execution, String role) {
        if (!"1".equals(requiredText(execution, "schemaVersion"))
                || !role.equals(requiredText(execution, "agentRole"))) {
            throw new SubmissionRejected("Execution context does not match the target role");
        }
        for (String field : List.of("taskId", "attemptId", "workflowId", "workflowRunId",
                "repositoryId", "delegationId")) {
            String value = requiredText(execution, field);
            if (value.length() > 200 || !value.matches("[A-Za-z0-9][A-Za-z0-9._:/-]*")) {
                throw new SubmissionRejected("Invalid execution context field " + field);
            }
        }
        if (!requiredText(execution, "sourceCommit").matches("[a-f0-9]{40}")) {
            throw new SubmissionRejected("Invalid source commit");
        }
        JsonNode digests = execution.path("inputDigests");
        if (!digests.isArray() || digests.isEmpty() || digests.size() > 32) {
            throw new SubmissionRejected("Invalid input digests");
        }
        digests.forEach(value -> {
            if (!value.asText().matches("[a-f0-9]{64}")) throw new SubmissionRejected("Invalid input digest");
        });
    }

    private static void requireScopes(Set<String> actual, Set<String> required) {
        if (actual == null || !actual.containsAll(required)) {
            throw new SubmissionRejected("Caller lacks required role or skill scope");
        }
    }

    private String digest(JsonNode message) {
        try {
            byte[] canonical = new JsonCanonicalizer(mapper.writeValueAsBytes(message)).getEncodedUTF8();
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (Exception exception) {
            throw new SubmissionRejected("Cannot digest A2A message", exception);
        }
    }

    private static JsonNode requiredObject(JsonNode source, String field) {
        JsonNode value = source == null ? null : source.get(field);
        if (value == null || !value.isObject()) throw new SubmissionRejected("Missing object " + field);
        return value;
    }

    private static String requiredText(JsonNode source, String field) {
        JsonNode value = source == null ? null : source.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new SubmissionRejected("Missing text " + field);
        }
        return value.asText();
    }

    public record Caller(String subject, Set<String> scopes) {
        public Caller { scopes = scopes == null ? Set.of() : Set.copyOf(scopes); }
    }

    public record Submission(
            String taskId,
            String contextId,
            String messageId,
            String role,
            String skill,
            String caller,
            String delegationId,
            Instant submittedAt) {}

    private record RegisteredSubmission(String messageDigest, Submission submission) {}

    public static final class SubmissionRejected extends RuntimeException {
        public SubmissionRejected(String message) { super(message); }
        public SubmissionRejected(String message, Throwable cause) { super(message, cause); }
    }
}
