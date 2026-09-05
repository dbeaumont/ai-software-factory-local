package com.example.aifactory.workflow.temporal;

import io.temporal.client.WorkflowOptions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.regex.Pattern;

/** Fail-closed policy for data persisted in Temporal workflow histories. */
final class TemporalPayloadGuard {
    static final int MAX_SERIALIZED_BYTES = 64 * 1024;
    private static final int MAX_TEXT_LENGTH = 4_096;
    private static final int MAX_DEPTH = 16;
    private static final int MAX_CONTAINER_SIZE = 256;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern SENSITIVE_KEY = Pattern.compile(
            "(?i).*(password|passwd|secret|api[_-]?key|access[_-]?token|private[_-]?key|authorization|cookie|credential).*");
    private static final Pattern SECRET_VALUE = Pattern.compile(
            "(?is).*(-----BEGIN [A-Z ]*PRIVATE KEY-----|bearer\\s+[A-Za-z0-9._~+/=-]{8,}|"
                    + "(?:password|passwd|client[_-]?secret|api[_-]?key|access[_-]?token)\\s*[:=]\\s*\\S+).*");
    private static final Pattern RAW_DIFF = Pattern.compile(
            "(?ms).*(^diff --git |^@@ -[0-9]|^Index: .+|^\\+\\+\\+ [ab]/).*");
    private static final Pattern CLASSIFIED_CONTENT = Pattern.compile(
            "(?i)^\\s*\\[?(CONFIDENTIAL|RESTRICTED|SECRET)\\]?[:\\s].*");
    private static final Pattern URI_CREDENTIALS = Pattern.compile(
            "(?i)^[a-z][a-z0-9+.-]*://[^/@\\s]+:[^/@\\s]+@.*");

    private TemporalPayloadGuard() {}

    static void requireSafeStart(WorkflowOptions options, Object input) {
        if (options == null || input == null) throw rejected();
        if (configured(options.getMemo()) || configured(options.getSearchAttributes())
                || options.getTypedSearchAttributes() != null && options.getTypedSearchAttributes().size() > 0
                || text(options.getStaticSummary()) || text(options.getStaticDetails())) {
            throw rejected();
        }
        requireSafePayload(input);
    }

    static void requireSafePayload(Object input) {
        if (input == null) throw rejected();
        try {
            byte[] serialized = JSON.writeValueAsBytes(input);
            if (serialized.length > MAX_SERIALIZED_BYTES) throw rejected();
            inspect(JSON.readTree(serialized), 0);
        } catch (SecurityException rejected) {
            throw rejected;
        } catch (Exception serializationFailure) {
            throw new SecurityException("Temporal payload cannot be policy-validated", serializationFailure);
        }
    }

    private static void inspect(JsonNode node, int depth) {
        if (node == null || depth > MAX_DEPTH) throw rejected();
        if (node.isTextual()) {
            String value = node.asText();
            if (value.length() > MAX_TEXT_LENGTH || SECRET_VALUE.matcher(value).matches()
                    || RAW_DIFF.matcher(value).matches() || CLASSIFIED_CONTENT.matcher(value).matches()) {
                throw rejected();
            }
            if (URI_CREDENTIALS.matcher(value).matches()) throw rejected();
            return;
        }
        if (node.isArray()) {
            if (node.size() > MAX_CONTAINER_SIZE) throw rejected();
            node.forEach(value -> inspect(value, depth + 1));
            return;
        }
        if (node.isObject()) {
            if (node.size() > MAX_CONTAINER_SIZE) throw rejected();
            node.properties().forEach(entry -> {
                if (SENSITIVE_KEY.matcher(entry.getKey()).matches()) throw rejected();
                inspect(entry.getValue(), depth + 1);
            });
        }
    }

    private static boolean configured(Map<?, ?> values) {
        return values != null && !values.isEmpty();
    }

    private static boolean text(String value) {
        return value != null && !value.isBlank();
    }

    private static SecurityException rejected() {
        return new SecurityException("Temporal payload violates the persisted-data policy");
    }
}
