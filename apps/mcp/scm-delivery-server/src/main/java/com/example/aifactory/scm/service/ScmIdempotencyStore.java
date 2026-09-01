package com.example.aifactory.scm.service;

import com.example.aifactory.scm.config.ScmDeliveryProperties;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;

@Service
public class ScmIdempotencyStore {
    private final Path root;
    private final ObjectMapper mapper;

    public ScmIdempotencyStore(ScmDeliveryProperties properties, ObjectMapper mapper) throws Exception {
        this.root = properties.stateRoot().resolve("idempotency");
        this.mapper = mapper;
        Files.createDirectories(root);
    }

    public synchronized ScmDeliveryBackend.DeliveryResult find(String key, String fingerprint) throws Exception {
        Path file = file(key);
        if (!Files.exists(file)) {
            return null;
        }
        Entry entry = mapper.readValue(Files.readString(file), Entry.class);
        if (!MessageDigest.isEqual(entry.fingerprint().getBytes(StandardCharsets.UTF_8),
                fingerprint.getBytes(StandardCharsets.UTF_8))) {
            throw new SecurityException("idempotency key was already used for another delivery");
        }
        return entry.result();
    }

    public synchronized void save(String key, String fingerprint, ScmDeliveryBackend.DeliveryResult result) throws Exception {
        Path target = file(key);
        Path temporary = root.resolve(target.getFileName() + ".tmp");
        Files.writeString(temporary, mapper.writeValueAsString(new Entry(fingerprint, result)));
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Path file(String key) throws Exception {
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(key.getBytes(StandardCharsets.UTF_8)));
        return root.resolve(digest + ".json");
    }

    public record Entry(String fingerprint, ScmDeliveryBackend.DeliveryResult result) {
    }
}
