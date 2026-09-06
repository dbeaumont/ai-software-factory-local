package com.example.aifactory.agentcore;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Loads immutable, classpath-pinned prompts without host filesystem access. */
public final class PromptRepository {
    public String load(String name) {
        if (name == null || !name.matches("[a-z][a-z0-9-]{1,63}")) {
            throw new IllegalArgumentException("Prompt name is invalid");
        }
        String resource = "prompts/" + name + ".md";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (input == null) throw new IllegalArgumentException("Unknown prompt " + name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot load prompt " + name, exception);
        }
    }

    public String fingerprint(String name) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(load(name).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot fingerprint prompt " + name, exception);
        }
    }
}
