package com.example.aifactory.service;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Verifies the immutable sandbox-to-SCM delivery lineage and derives its audit correlation id. */
@Component
public final class DeliveryCorrelationVerifier {
    public VerifiedCorrelation verify(DeliveryCorrelation value) {
        requireIdentity(value);
        if (value.sandboxJobs() == null || value.sandboxJobs().isEmpty()
                || value.evidenceDigests() == null || value.evidenceDigests().isEmpty()
                || value.assurance() == null || value.manifest() == null || value.scmDelivery() == null) {
            throw invalid("delivery stages are incomplete");
        }
        Set<String> executions = new HashSet<>();
        Set<String> evidenceValues = Set.copyOf(value.evidenceDigests().values());
        for (SandboxJob job : value.sandboxJobs()) {
            if (job == null || !hex(job.executionId(), 32) || !hex(job.outputDigest(), 64)
                    || !"PASSED".equals(job.verdict()) || !executions.add(job.executionId())
                    || !evidenceValues.contains(job.outputDigest())) {
                throw invalid("sandbox job is unverified or absent from evidence");
            }
        }
        requireDigests(value.evidenceDigests(), "evidence");
        requireDigests(value.assurance().inputDigests(), "assurance input");
        if (!Set.copyOf(value.assurance().inputDigests().values()).containsAll(evidenceValues)
                || !Set.of("ALLOW", "PASSED").contains(value.assurance().verdict())) {
            throw invalid("assurance verdict does not bind every evidence digest");
        }
        if (!hex(value.manifest().manifestId(), 64) || !hex(value.manifest().digest(), 64)
                || !"COMPLETE".equals(value.manifest().status())) {
            throw invalid("manifest is incomplete");
        }
        ScmDelivery delivery = value.scmDelivery();
        if (!value.sourceCommit().equals(delivery.sourceCommit())
                || !value.manifest().manifestId().equals(delivery.manifestId())
                || !value.manifest().digest().equals(delivery.manifestDigest())
                || delivery.pullRequestId() < 1 || delivery.pullRequestUrl() == null
                || !delivery.pullRequestUrl().matches("https?://[^\\s]{1,2030}")) {
            throw invalid("SCM delivery is not bound to source and manifest");
        }
        return new VerifiedCorrelation(correlationId(value), value.taskId(), value.attemptId(),
                value.sourceCommit(), executions.size(), value.assurance().verdict(),
                value.manifest().manifestId(), delivery.pullRequestId(), delivery.pullRequestUrl());
    }

    private static void requireIdentity(DeliveryCorrelation value) {
        if (value == null || !id(value.taskId()) || !id(value.attemptId())
                || !hex(value.traceId(), 32) || !hex(value.sourceCommit(), 40)) {
            throw invalid("workflow identity is invalid");
        }
    }

    private static void requireDigests(Map<String, String> values, String field) {
        if (values == null || values.isEmpty() || values.keySet().stream().anyMatch(key -> !id(key))
                || values.values().stream().anyMatch(value -> !hex(value, 64))) {
            throw invalid(field + " digests are invalid");
        }
    }

    private static String correlationId(DeliveryCorrelation value) {
        try {
            List<String> fields = new ArrayList<>(List.of(value.traceId(), value.taskId(), value.attemptId(),
                    value.sourceCommit(), value.assurance().verdict(), value.manifest().manifestId(),
                    value.manifest().digest(), Long.toString(value.scmDelivery().pullRequestId())));
            value.sandboxJobs().stream().map(job -> job.executionId() + ':' + job.outputDigest()).sorted()
                    .forEach(fields::add);
            value.evidenceDigests().entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .map(entry -> entry.getKey() + ':' + entry.getValue()).forEach(fields::add);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(String.join("\n", fields).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static boolean id(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    }

    private static boolean hex(String value, int size) {
        return value != null && value.matches("[0-9a-f]{" + size + "}");
    }

    private static SecurityException invalid(String reason) {
        return new SecurityException("Delivery correlation rejected: " + reason);
    }

    public record DeliveryCorrelation(String traceId, String taskId, String attemptId, String sourceCommit,
                                      List<SandboxJob> sandboxJobs, Map<String, String> evidenceDigests,
                                      AssuranceDecision assurance, Manifest manifest, ScmDelivery scmDelivery) {
        public DeliveryCorrelation {
            sandboxJobs = sandboxJobs == null ? null : List.copyOf(sandboxJobs);
            evidenceDigests = evidenceDigests == null ? null : Map.copyOf(evidenceDigests);
        }
    }

    public record SandboxJob(String executionId, String operation, String verdict, String outputDigest) {}

    public record AssuranceDecision(String verdict, Map<String, String> inputDigests) {
        public AssuranceDecision {
            inputDigests = inputDigests == null ? null : Map.copyOf(inputDigests);
        }
    }

    public record Manifest(String manifestId, String digest, String status) {}

    public record ScmDelivery(String sourceCommit, String manifestId, String manifestDigest,
                              long pullRequestId, String pullRequestUrl) {}

    public record VerifiedCorrelation(String correlationId, String taskId, String attemptId, String sourceCommit,
                                      int sandboxJobCount, String assuranceVerdict, String manifestId,
                                      long pullRequestId, String pullRequestUrl) {}
}
