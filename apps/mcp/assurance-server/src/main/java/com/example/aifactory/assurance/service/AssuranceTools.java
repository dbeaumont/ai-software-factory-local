package com.example.aifactory.assurance.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

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
}
