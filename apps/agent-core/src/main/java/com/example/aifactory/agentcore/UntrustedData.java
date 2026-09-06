package com.example.aifactory.agentcore;

import java.nio.charset.StandardCharsets;

/** Host-owned delimiter for all repository, ticket, A2A and tool content sent to a model. */
public final class UntrustedData {
    private static final int MAX_BYTES = 1_048_576;

    private UntrustedData() {}

    public static String wrap(String label, String content) {
        if (label == null || !label.matches("[a-z][a-z0-9_]{1,63}") || content == null
                || content.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalArgumentException("Untrusted data boundary is invalid or oversized");
        }
        String closing = "</untrusted_" + label + ">";
        String escaped = content.replace(closing, "&lt;/untrusted_" + label + "&gt;")
                .replace("\u0000", "");
        return "<untrusted_" + label + " trust=\"none\">\n" + escaped + "\n" + closing;
    }
}
