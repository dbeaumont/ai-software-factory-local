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

/** Builds the stable final consolidation view consumed by independent review and human operators. */
@Component
public final class FinalConsolidationSummaryBuilder {
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final Pattern COMMIT = Pattern.compile("[a-f0-9]{40}");
    private static final Pattern DIGEST = Pattern.compile("[a-f0-9]{64}");

    public Summary build(Request request) {
        requireRequest(request);
        List<DecisionSummary> decisions = request.arbitrations().stream()
                .map(entry -> decision(request, entry))
                .sorted(Comparator.comparing(DecisionSummary::contradictionId)
                        .thenComparing(DecisionSummary::arbitrationId)).toList();
        List<ResidualRisk> risks = request.residualRisks().stream()
                .sorted(Comparator.comparing(ResidualRisk::severity).reversed()
                        .thenComparing(ResidualRisk::riskId)).toList();
        List<HumanPoint> humanPoints = request.humanPoints().stream()
                .sorted(Comparator.comparing(HumanPoint::requestId)).toList();
        requireUnique(decisions.stream().map(DecisionSummary::arbitrationId).toList(), "arbitration");
        requireUnique(risks.stream().map(ResidualRisk::riskId).toList(), "risk");
        requireUnique(humanPoints.stream().map(HumanPoint::requestId).toList(), "human decision");
        risks.forEach(FinalConsolidationSummaryBuilder::requireRisk);
        humanPoints.forEach(FinalConsolidationSummaryBuilder::requireHumanPoint);

        Status status = humanPoints.isEmpty() ? Status.READY_FOR_INDEPENDENT_REVIEW
                : Status.HUMAN_DECISION_REQUIRED;
        String digest = digest(request, status, decisions, risks, humanPoints);
        return new Summary("summary-" + digest.substring(0, 24), request.taskId(), request.attemptId(),
                request.sourceCommit(), status, decisions, risks, humanPoints, request.generatedAt(), digest);
    }

    private static DecisionSummary decision(Request request, ArbitrationJournal.Entry entry) {
        if (entry == null || !request.taskId().equals(entry.taskId())
                || !request.attemptId().equals(entry.attemptId())
                || !request.sourceCommit().equals(entry.sourceCommit())
                || entry.evidence().isEmpty() || !DIGEST.matcher(entry.recordDigest()).matches()) {
            throw new IllegalArgumentException("Arbitration is invalid or outside final summary lineage");
        }
        List<String> evidenceUris = entry.evidence().stream().map(ArbitrationJournal.EvidenceReference::uri)
                .distinct().sorted().toList();
        return new DecisionSummary(entry.arbitrationId(), entry.contradictionId(), entry.ruleId(),
                entry.ruleVersion(), entry.decision(), entry.author(), entry.authorType(), evidenceUris,
                entry.recordDigest());
    }

    private static void requireRequest(Request request) {
        if (request == null || !id(request.taskId()) || !id(request.attemptId())
                || request.sourceCommit() == null || !COMMIT.matcher(request.sourceCommit()).matches()
                || request.arbitrations() == null || request.residualRisks() == null
                || request.humanPoints() == null) {
            throw new IllegalArgumentException("Final consolidation request is invalid");
        }
        try {
            OffsetDateTime.parse(request.generatedAt());
        } catch (DateTimeParseException | NullPointerException invalid) {
            throw new IllegalArgumentException("Final consolidation timestamp is invalid", invalid);
        }
    }

    private static void requireRisk(ResidualRisk risk) {
        if (risk == null || !id(risk.riskId()) || risk.severity() == null || risk.description() == null
                || risk.description().isBlank() || risk.description().length() > 2_000
                || risk.mitigation() == null || risk.mitigation().isBlank() || risk.mitigation().length() > 2_000
                || risk.evidenceUris() == null || risk.evidenceUris().stream().anyMatch(uri -> uri == null
                || !uri.startsWith("evidence://") || uri.length() > 1_024)) {
            throw new IllegalArgumentException("Residual risk is invalid");
        }
    }

    private static void requireHumanPoint(HumanPoint point) {
        if (point == null || !id(point.requestId()) || !id(point.contradictionId()) || point.domain() == null
                || point.objectDigest() == null || !DIGEST.matcher(point.objectDigest()).matches()
                || point.question() == null || point.question().isBlank() || point.question().length() > 1_000) {
            throw new IllegalArgumentException("Human decision point is invalid");
        }
    }

    private static void requireUnique(List<String> ids, String kind) {
        if (ids.size() != new HashSet<>(ids).size()) {
            throw new IllegalArgumentException("Duplicate " + kind + " in final summary");
        }
    }

    private static String digest(Request request, Status status, List<DecisionSummary> decisions,
                                 List<ResidualRisk> risks, List<HumanPoint> humanPoints) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : List.of("1", request.taskId(), request.attemptId(), request.sourceCommit(),
                    status.name(), request.generatedAt())) update(digest, value);
            decisions.forEach(value -> {
                for (String field : List.of(value.arbitrationId(), value.contradictionId(), value.ruleId(),
                        value.ruleVersion(), value.decision(), value.author(), value.authorType().name(),
                        value.recordDigest())) update(digest, field);
                value.evidenceUris().forEach(uri -> update(digest, uri));
            });
            risks.forEach(value -> {
                for (String field : List.of(value.riskId(), value.severity().name(), value.description(),
                        value.mitigation())) update(digest, field);
                value.evidenceUris().stream().sorted().forEach(uri -> update(digest, uri));
            });
            humanPoints.forEach(value -> {
                for (String field : List.of(value.requestId(), value.contradictionId(), value.domain().name(),
                        value.objectDigest(), value.question())) update(digest, field);
            });
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

    private static boolean id(String value) { return value != null && ID.matcher(value).matches(); }

    public record Request(String taskId, String attemptId, String sourceCommit,
                          List<ArbitrationJournal.Entry> arbitrations, List<ResidualRisk> residualRisks,
                          List<HumanPoint> humanPoints, String generatedAt) {
        public Request {
            arbitrations = arbitrations == null ? null : List.copyOf(arbitrations);
            residualRisks = residualRisks == null ? null : List.copyOf(residualRisks);
            humanPoints = humanPoints == null ? null : List.copyOf(humanPoints);
        }
    }

    public record Summary(String summaryId, String taskId, String attemptId, String sourceCommit, Status status,
                          List<DecisionSummary> decisions, List<ResidualRisk> residualRisks,
                          List<HumanPoint> humanPoints, String generatedAt, String digest) {
        public Summary {
            decisions = List.copyOf(decisions);
            residualRisks = List.copyOf(residualRisks);
            humanPoints = List.copyOf(humanPoints);
        }
    }

    public record DecisionSummary(String arbitrationId, String contradictionId, String ruleId, String ruleVersion,
                                  String decision, String author, ArbitrationJournal.AuthorType authorType,
                                  List<String> evidenceUris, String recordDigest) {
        public DecisionSummary { evidenceUris = List.copyOf(evidenceUris); }
    }

    public record ResidualRisk(String riskId, Severity severity, String description, String mitigation,
                               List<String> evidenceUris) {
        public ResidualRisk { evidenceUris = evidenceUris == null ? null : List.copyOf(evidenceUris); }
    }

    public record HumanPoint(String requestId, String contradictionId, HumanDecisionEscalator.DecisionDomain domain,
                             String objectDigest, String question) {}

    public enum Severity { NONE, LOW, MEDIUM, HIGH, CRITICAL, UNKNOWN }

    public enum Status { READY_FOR_INDEPENDENT_REVIEW, HUMAN_DECISION_REQUIRED }
}
