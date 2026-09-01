package com.example.aifactory.scm.service;

import com.example.aifactory.scm.config.ScmDeliveryProperties;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class ScmAuditLog {
    private final Path file;
    private final ObjectMapper mapper;

    public ScmAuditLog(ScmDeliveryProperties properties, ObjectMapper mapper) throws Exception {
        Path auditRoot = properties.stateRoot().resolve("audit");
        Files.createDirectories(auditRoot);
        this.file = auditRoot.resolve("scm-delivery.jsonl");
        this.mapper = mapper;
    }

    public synchronized void before(ScmDeliveryService.CreateRequest request, String branch) throws Exception {
        Map<String, Object> event = base("BEFORE_WRITE", request, branch);
        event.put("source_commit", request.sourceCommit());
        event.put("patch_digest", request.patchDigest());
        append(event);
    }

    public synchronized void after(ScmDeliveryService.CreateRequest request, ScmDeliveryBackend.DeliveryResult result)
            throws Exception {
        Map<String, Object> event = base("AFTER_WRITE", request, result.branch());
        event.put("commit", result.commit());
        event.put("pull_request_id", result.pullRequestId());
        event.put("pull_request_url", result.pullRequestUrl());
        append(event);
    }

    private Map<String, Object> base(String phase, ScmDeliveryService.CreateRequest request, String branch) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("timestamp", Instant.now().toString());
        event.put("phase", phase);
        event.put("action", "CREATE_DRAFT_PULL_REQUEST");
        event.put("task_id", request.taskId());
        event.put("attempt_id", request.attemptId());
        event.put("repository_id", request.repositoryId());
        event.put("branch", branch);
        event.put("idempotency_key", request.idempotencyKey());
        event.put("actor", request.actor());
        return event;
    }

    private void append(Map<String, Object> event) throws Exception {
        byte[] bytes = (mapper.writeValueAsString(event) + "\n").getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.APPEND)) {
            channel.write(ByteBuffer.wrap(bytes));
            channel.force(true);
        }
    }
}
