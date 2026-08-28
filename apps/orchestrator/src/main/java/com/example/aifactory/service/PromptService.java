package com.example.aifactory.service;

import com.example.aifactory.config.AiFactoryProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@Service
public class PromptService {
    private final Path root;

    @Autowired
    public PromptService(AiFactoryProperties props) {
        this(Path.of(requirePromptRoot(props.promptRoot())));
    }

    PromptService(Path root) {
        this.root = root;
    }

    public String load(String name) {
        Path prompt = root.resolve(name + ".md").toAbsolutePath().normalize();
        try {
            return Files.readString(prompt);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load prompt " + name + " from " + prompt, e);
        }
    }

    /**
     * Returns an immutable identifier for the exact prompt content used by a run.
     * The content itself is deliberately not copied into task metadata.
     */
    public String fingerprint(String name) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(load(name).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static String requirePromptRoot(String promptRoot) {
        if (promptRoot == null || promptRoot.isBlank()) {
            throw new IllegalStateException("AI_FACTORY_PROMPT_ROOT must be configured");
        }
        return promptRoot;
    }
}
