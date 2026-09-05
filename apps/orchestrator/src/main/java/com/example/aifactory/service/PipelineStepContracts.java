package com.example.aifactory.service;

import com.example.aifactory.model.TaskState;
import com.example.aifactory.workflow.temporal.TemporalIds;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/** Versioned, size-bounded payloads crossing a pipeline step boundary. */
public final class PipelineStepContracts {
    public static final int SCHEMA_VERSION = 1;
    public static final String INITIAL_ATTEMPT_ID = "pipeline-1";
    public static final String UNRESOLVED_SOURCE_COMMIT = "UNRESOLVED";

    private PipelineStepContracts() {}

    public record Command(int schemaVersion, String step, String taskId, String attemptId, String workflowId,
                          String repositoryId, String sourceCommit, Map<String, String> inputDigests) {
        public Command {
            requireVersion(schemaVersion);
            requireToken("step", step, 64);
            requireToken("taskId", taskId, 128);
            requireToken("attemptId", attemptId, 128);
            requireText("workflowId", workflowId, 200);
            requireToken("repositoryId", repositoryId, 128);
            requireSourceCommit(sourceCommit);
            inputDigests = immutableDigests(inputDigests, 32);
        }

        public void requireStep(String expected) {
            if (!step.equals(expected)) throw new IllegalArgumentException("Unexpected pipeline step: " + step);
        }

        public static Command forTask(TaskState state, String step, Map<String, String> inputs) {
            String sourceCommit = state.sourceCommit == null ? UNRESOLVED_SOURCE_COMMIT : state.sourceCommit;
            return new Command(SCHEMA_VERSION, step, state.id, INITIAL_ATTEMPT_ID,
                    TemporalIds.workflow(state.id, INITIAL_ATTEMPT_ID),
                    ScmDeliveryGateway.repositoryId(state.request.repositoryUrl()), sourceCommit,
                    digestValues(inputs));
        }
    }

    public record Result(int schemaVersion, String step, String taskId, String attemptId, String sourceCommit,
                         Map<String, String> outputDigests) {
        public Result {
            requireVersion(schemaVersion);
            requireToken("step", step, 64);
            requireToken("taskId", taskId, 128);
            requireToken("attemptId", attemptId, 128);
            requireSourceCommit(sourceCommit);
            outputDigests = immutableDigests(outputDigests, 32);
        }

        public static Result from(Command command, String sourceCommit, Map<String, String> outputs) {
            return new Result(SCHEMA_VERSION, command.step(), command.taskId(), command.attemptId(), sourceCommit,
                    digestValues(outputs));
        }
    }

    private static Map<String, String> digestValues(Map<String, String> values) {
        if (values == null || values.size() > 32) throw new IllegalArgumentException("Pipeline inputs are invalid");
        Map<String, String> digests = new TreeMap<>();
        values.forEach((name, value) -> {
            requireToken("digest name", name, 64);
            if (value == null || value.length() > 1_000_000) {
                throw new IllegalArgumentException("Pipeline input is missing or too large: " + name);
            }
            digests.put(name, sha256(value));
        });
        return Map.copyOf(digests);
    }

    private static Map<String, String> immutableDigests(Map<String, String> digests, int maximumSize) {
        if (digests == null || digests.size() > maximumSize || digests.entrySet().stream().anyMatch(entry -> {
            try {
                requireToken("digest name", entry.getKey(), 64);
                return entry.getValue() == null || !entry.getValue().matches("[0-9a-f]{64}");
            } catch (IllegalArgumentException exception) {
                return true;
            }
        })) throw new IllegalArgumentException("Pipeline digests are invalid");
        return Map.copyOf(new TreeMap<>(digests));
    }

    private static void requireVersion(int schemaVersion) {
        if (schemaVersion != SCHEMA_VERSION) throw new IllegalArgumentException("Unsupported pipeline schema");
    }

    private static void requireSourceCommit(String value) {
        if (!UNRESOLVED_SOURCE_COMMIT.equals(value) && (value == null || !value.matches("[0-9a-f]{7,64}"))) {
            throw new IllegalArgumentException("Source commit is invalid");
        }
    }

    private static void requireToken(String name, String value, int max) {
        if (value == null || value.isBlank() || value.length() > max || !value.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException(name + " is invalid");
        }
    }

    private static void requireText(String name, String value, int max) {
        if (value == null || value.isBlank() || value.length() > max) throw new IllegalArgumentException(name + " is invalid");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot digest pipeline payload", exception);
        }
    }
}
