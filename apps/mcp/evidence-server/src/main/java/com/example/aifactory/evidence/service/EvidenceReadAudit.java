package com.example.aifactory.evidence.service;

import com.example.aifactory.evidence.config.EvidenceProperties;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Map;

@Service
public class EvidenceReadAudit {
    private final Path file;
    private final ObjectMapper mapper;
    public EvidenceReadAudit(EvidenceProperties properties, ObjectMapper mapper) throws Exception {
        file = properties.stateRoot().resolve("audit/raw-reads.jsonl");
        Files.createDirectories(file.getParent());
        this.mapper = mapper;
    }
    public synchronized void record(String taskId, String actor, String purpose, String uri, String outcome) {
        try {
            byte[] line = (mapper.writeValueAsString(Map.of("at", Instant.now().toString(), "task_id", taskId,
                    "actor", actor, "purpose", purpose, "uri", uri, "outcome", outcome)) + "\n")
                    .getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND)) { channel.write(java.nio.ByteBuffer.wrap(line)); channel.force(true); }
        } catch (Exception exception) { throw new IllegalStateException("evidence read audit cannot be persisted", exception); }
    }
}
