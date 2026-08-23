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
        this.root = Path.of(props.promptRoot());
    }

    public String load(String name) {
        try {
            return Files.readString(root.resolve(name + ".md"));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load prompt " + name, e);
        }
    }
}
