package com.example.aifactory.service;

import com.example.aifactory.config.AiFactoryProperties;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Service
public class PromptService {
    private final Path root;

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

    private static String requirePromptRoot(String promptRoot) {
        if (promptRoot == null || promptRoot.isBlank()) {
            throw new IllegalStateException("AI_FACTORY_PROMPT_ROOT must be configured");
        }
        return promptRoot;
    }
}
