package com.example.aifactory.assurance.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.LinkedHashMap;

@Service
public class AssuranceTools {
    @Tool(name = "assurance.evaluate_quality_gate", description = "Normalize bounded Sonar quality evidence into a fail-closed quality gate decision")
    public QualityGateResult evaluateQualityGate(
            @ToolParam(description = "Contract version, currently 1") String schema_version,
            @ToolParam(description = "Task identifier") String task_id,
            @ToolParam(description = "Attempt identifier") String attempt_id,
            @ToolParam(description = "Immutable source commit") String source_commit,
            @ToolParam(description = "Scanner provider, currently SonarQube") String provider,
            @ToolParam(description = "Quality gate name") String gate,
            @ToolParam(description = "Technical execution status") String technical_status,
            @ToolParam(description = "Process exit code") Integer exit_code,
            @ToolParam(description = "Evidence status") String evidence_status,
            @ToolParam(description = "Bounded scanner output") String output,
            @ToolParam(description = "Internal evidence URI") String evidence_uri,
            @ToolParam(description = "SHA-256 evidence digest") String evidence_digest) {
        validate(schema_version, task_id, attempt_id, source_commit, provider, gate, technical_status,
                evidence_status, output, evidence_uri, evidence_digest);
        String upper = output.toUpperCase(java.util.Locale.ROOT);
        Verdict verdict;
        String reason;
        if (!"SUCCEEDED".equals(technical_status) || exit_code == null || !"COMPLETE".equals(evidence_status)) {
            verdict = Verdict.INDETERMINATE;
            reason = "technical execution or evidence is incomplete";
        } else if (upper.contains("QUALITY GATE STATUS: FAILED") || upper.contains("QUALITY GATE STATUS: ERROR")) {
            verdict = Verdict.REJECTED;
            reason = "quality gate rejected by provider";
        } else if (exit_code == 0 && upper.contains("QUALITY GATE STATUS: PASSED")) {
            verdict = Verdict.PASSED;
            reason = "quality gate passed";
        } else {
            verdict = Verdict.INDETERMINATE;
            reason = "recognized quality gate status is absent";
        }
        return new QualityGateResult("1", task_id, attempt_id, source_commit, provider, gate, verdict.name(),
                List.of(), new Evidence(evidence_uri, evidence_digest, evidence_status), reason, Instant.now());
    }

    @Tool(name = "assurance.normalize_findings", description = "Normalize bounded SonarQube or Trivy findings into portable severities and evidence")
    public VulnerabilityResult normalizeFindings(
            @ToolParam(description = "Contract version, currently 1") String schema_version,
            @ToolParam(description = "Task identifier") String task_id,
            @ToolParam(description = "Attempt identifier") String attempt_id,
            @ToolParam(description = "Immutable source commit") String source_commit,
            @ToolParam(description = "Scanner: SonarQube or Trivy") String scanner,
            @ToolParam(description = "Extracted scanner findings") List<RawFinding> findings,
            @ToolParam(description = "Internal evidence URI") String evidence_uri,
            @ToolParam(description = "SHA-256 evidence digest") String evidence_digest,
            @ToolParam(description = "Evidence status") String evidence_status) {
        validate("1", task_id, attempt_id, source_commit, "SonarQube", "normalization", "SUCCEEDED",
                evidence_status, "", evidence_uri, evidence_digest);
        if (!"1".equals(schema_version) || !("SonarQube".equals(scanner) || "Trivy".equals(scanner))
                || findings == null || findings.size() > 4096) {
            throw new IllegalArgumentException("invalid finding normalization request");
        }
        List<NormalizedFinding> normalized = findings.stream().map(this::normalize).toList();
        Map<String, Integer> summary = new LinkedHashMap<>();
        for (String severity : List.of("UNKNOWN", "LOW", "MEDIUM", "HIGH", "CRITICAL")) {
            summary.put(severity.toLowerCase(Locale.ROOT), 0);
        }
        normalized.forEach(finding -> summary.computeIfPresent(finding.severity().toLowerCase(Locale.ROOT),
                (ignored, count) -> count + 1));
        String verdict = !"COMPLETE".equals(evidence_status) || summary.get("unknown") > 0 ? "INDETERMINATE"
                : summary.get("high") > 0 || summary.get("critical") > 0 ? "REJECTED" : "PASSED";
        return new VulnerabilityResult("1", task_id, attempt_id, source_commit, scanner, verdict, normalized,
                summary, new Evidence(evidence_uri, evidence_digest, evidence_status),
                "INDETERMINATE".equals(verdict) ? "unknown severity or incomplete evidence" : null);
    }

    private NormalizedFinding normalize(RawFinding finding) {
        if (finding == null || blank(finding.id()) || blank(finding.component()) || blank(finding.rule())
                || blank(finding.proof()) || blank(finding.recommendation())) {
            throw new IllegalArgumentException("finding lacks mandatory normalized context");
        }
        return new NormalizedFinding(limit(finding.id(), 128), severity(finding.severity()),
                limit(finding.component(), 512), nullableLimit(finding.file(), 1024), limit(finding.rule(), 256),
                limit(finding.proof(), 2048), limit(finding.recommendation(), 2048));
    }

    private static String severity(String raw) {
        if (raw == null) return "UNKNOWN";
        return switch (raw.toUpperCase(Locale.ROOT)) {
            case "INFO", "INFORMATIONAL", "NEGLIGIBLE", "LOW" -> "LOW";
            case "MINOR", "MEDIUM", "MODERATE" -> "MEDIUM";
            case "MAJOR", "HIGH" -> "HIGH";
            case "BLOCKER", "CRITICAL" -> "CRITICAL";
            default -> "UNKNOWN";
        };
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String limit(String value, int maximum) { return value.strip().substring(0, Math.min(value.strip().length(), maximum)); }
    private static String nullableLimit(String value, int maximum) { return blank(value) ? null : limit(value, maximum); }

    private static void validate(String schemaVersion, String taskId, String attemptId, String sourceCommit,
                                 String provider, String gate, String technicalStatus, String evidenceStatus,
                                 String output, String evidenceUri, String evidenceDigest) {
        if (!"1".equals(schemaVersion) || taskId == null || !taskId.matches("[A-Za-z0-9_-]{1,64}")
                || attemptId == null || !attemptId.matches("[A-Za-z0-9_-]{1,128}")
                || sourceCommit == null || !sourceCommit.matches("[0-9a-f]{40}")
                || !"SonarQube".equals(provider) || gate == null || gate.isBlank()
                || technicalStatus == null || evidenceStatus == null || output == null || output.length() > 65536
                || evidenceUri == null || !evidenceUri.startsWith("evidence://")
                || evidenceDigest == null || !evidenceDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid quality gate evaluation request");
        }
    }

    enum Verdict { PASSED, REJECTED, INDETERMINATE }

    public record Evidence(String uri, String digest, String status) {}
    public record QualityGateResult(@JsonProperty("schema_version") String schemaVersion,
                                    @JsonProperty("task_id") String taskId,
                                    @JsonProperty("attempt_id") String attemptId,
                                    @JsonProperty("source_commit") String sourceCommit,
                                    String provider, String gate, String verdict, List<Condition> conditions,
                                    Evidence evidence, String reason,
                                    @JsonProperty("evaluated_at") Instant evaluatedAt) {}
    public record Condition(String metric, String operator, Object threshold, Object actual, String status) {}
    public record RawFinding(String id, String severity, String component, String file, String rule,
                             String proof, String recommendation) {}
    public record NormalizedFinding(String id, String severity, String component, String file, String rule,
                                    String proof, String recommendation) {}
    public record VulnerabilityResult(@JsonProperty("schema_version") String schemaVersion,
                                      @JsonProperty("task_id") String taskId,
                                      @JsonProperty("attempt_id") String attemptId,
                                      @JsonProperty("source_commit") String sourceCommit,
                                      String scanner, String verdict, List<NormalizedFinding> findings,
                                      Map<String, Integer> summary, Evidence evidence, String reason) {}
}
