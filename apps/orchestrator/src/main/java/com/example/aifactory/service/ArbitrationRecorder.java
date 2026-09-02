package com.example.aifactory.service;

import com.example.aifactory.workflow.ArbitrationJournal;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Validates, canonicalizes and appends complete arbitration records. */
@Component
public final class ArbitrationRecorder {
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final Pattern COMMIT = Pattern.compile("[a-f0-9]{40}");
    private static final Pattern DIGEST = Pattern.compile("[a-f0-9]{64}");
    private final ArbitrationJournal journal;

    public ArbitrationRecorder(ArbitrationJournal journal) {
        this.journal = journal;
    }

    public ArbitrationJournal.Entry record(Request request) {
        requireRequest(request);
        List<ArbitrationJournal.InputReference> inputs = request.inputs().stream()
                .sorted(Comparator.comparing(ArbitrationJournal.InputReference::inputId)).toList();
        List<ArbitrationJournal.EvidenceReference> evidence = request.evidence().stream()
                .sorted(Comparator.comparing(ArbitrationJournal.EvidenceReference::uri)).toList();
        requireReferences(inputs, evidence);
        String recordDigest = digest(request, inputs, evidence);
        String arbitrationId = "arbitration-" + recordDigest.substring(0, 24);
        ArbitrationJournal.Entry entry = new ArbitrationJournal.Entry(arbitrationId, request.taskId(),
                request.attemptId(), request.sourceCommit(), request.contradictionId(), request.ruleId(),
                request.ruleVersion(), request.decision(), request.author(), request.authorType(), inputs,
                evidence, recordDigest, request.decidedAt());
        journal.append(entry);
        return entry;
    }

    private static void requireRequest(Request request) {
        if (request == null || !id(request.taskId()) || !id(request.attemptId())
                || !id(request.contradictionId()) || !id(request.ruleId())
                || request.sourceCommit() == null || !COMMIT.matcher(request.sourceCommit()).matches()
                || request.ruleVersion() == null || request.ruleVersion().isBlank()
                || request.ruleVersion().length() > 32 || request.decision() == null
                || request.decision().isBlank() || request.decision().length() > 128
                || request.author() == null || request.author().isBlank() || request.author().length() > 256
                || request.authorType() == null || request.inputs() == null || request.inputs().isEmpty()
                || request.evidence() == null || request.evidence().isEmpty()) {
            throw new IllegalArgumentException("Arbitration record is incomplete");
        }
        try {
            OffsetDateTime.parse(request.decidedAt());
        } catch (DateTimeParseException | NullPointerException invalid) {
            throw new IllegalArgumentException("Arbitration decision timestamp is invalid", invalid);
        }
    }

    private static void requireReferences(List<ArbitrationJournal.InputReference> inputs,
                                          List<ArbitrationJournal.EvidenceReference> evidence) {
        Set<String> inputIds = new HashSet<>();
        if (inputs.size() > 64 || inputs.stream().anyMatch(input -> input == null || !id(input.inputId())
                || input.digest() == null || !DIGEST.matcher(input.digest()).matches()
                || !inputIds.add(input.inputId()))) {
            throw new IllegalArgumentException("Arbitration input references are invalid");
        }
        Set<String> uris = new HashSet<>();
        if (evidence.size() > 64 || evidence.stream().anyMatch(reference -> reference == null
                || reference.uri() == null || !reference.uri().startsWith("evidence://")
                || reference.uri().length() > 1_024 || reference.digest() == null
                || !DIGEST.matcher(reference.digest()).matches() || !uris.add(reference.uri()))) {
            throw new IllegalArgumentException("Arbitration evidence references are invalid");
        }
    }

    private static String digest(Request request, List<ArbitrationJournal.InputReference> inputs,
                                 List<ArbitrationJournal.EvidenceReference> evidence) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : List.of(request.taskId(), request.attemptId(), request.sourceCommit(),
                    request.contradictionId(), request.ruleId(), request.ruleVersion(), request.decision(),
                    request.author(), request.authorType().name(), request.decidedAt())) update(digest, value);
            inputs.forEach(input -> { update(digest, input.inputId()); update(digest, input.digest()); });
            evidence.forEach(reference -> { update(digest, reference.uri()); update(digest, reference.digest()); });
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static boolean id(String value) {
        return value != null && ID.matcher(value).matches();
    }

    public record Request(String taskId, String attemptId, String sourceCommit, String contradictionId,
                          String ruleId, String ruleVersion, String decision, String author,
                          ArbitrationJournal.AuthorType authorType,
                          List<ArbitrationJournal.InputReference> inputs,
                          List<ArbitrationJournal.EvidenceReference> evidence, String decidedAt) {
        public Request {
            inputs = inputs == null ? null : List.copyOf(inputs);
            evidence = evidence == null ? null : List.copyOf(evidence);
        }
    }
}
