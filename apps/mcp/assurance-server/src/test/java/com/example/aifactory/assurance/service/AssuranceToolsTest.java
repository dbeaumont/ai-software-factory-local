package com.example.aifactory.assurance.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.util.List;
import java.util.Map;

class AssuranceToolsTest {
    private final AssuranceTools tools = new AssuranceTools();

    @Test
    void classifiesKnownPassFailureAndUnknownOutput() {
        assertEquals("PASSED", evaluate("SUCCEEDED", 0, "COMPLETE", "QUALITY GATE STATUS: PASSED").verdict());
        assertEquals("REJECTED", evaluate("SUCCEEDED", 1, "COMPLETE", "QUALITY GATE STATUS: FAILED").verdict());
        assertEquals("INDETERMINATE", evaluate("SUCCEEDED", 0, "COMPLETE", "ANALYSIS SUCCESSFUL").verdict());
        assertEquals("INDETERMINATE", evaluate("TIMED_OUT", null, "PARTIAL", "QUALITY GATE STATUS: PASSED").verdict());
    }

    @Test
    void normalizesSonarAndTrivySeverityVocabulary() {
        AssuranceTools.RawFinding sonar = new AssuranceTools.RawFinding("S-1", "BLOCKER", "customer-api",
                "src/App.java", "java:S1", "unsafe call", "replace unsafe call");
        AssuranceTools.RawFinding trivy = new AssuranceTools.RawFinding("CVE-1", "HIGH", "lib:1", null,
                "CVE-1", "installed version 1", "upgrade to version 2");
        AssuranceTools.VulnerabilityResult result = tools.normalizeFindings("1", "task-1", "attempt-1",
                "a".repeat(40), "SonarQube", List.of(sonar, trivy), "evidence://task-1/attempt-1/findings",
                "b".repeat(64), "COMPLETE", "workflow");

        assertEquals(List.of("CRITICAL", "HIGH"), result.findings().stream().map(AssuranceTools.NormalizedFinding::severity).toList());
        assertEquals("REJECTED", result.verdict());
        assertEquals(1, result.summary().get("critical"));
    }

    @Test
    void policyBlocksMissingPartialUnknownAndRejectedEvidence() {
        Map<String, String> digests = Map.of("tests", "b".repeat(64), "quality", "b".repeat(64),
                "security", "b".repeat(64), "sbom", "b".repeat(64));
        assertEquals("ALLOW", policy(Map.of("tests", "PASSED", "quality", "PASSED", "security", "PASSED", "sbom", "PASSED"), digests));
        assertEquals("DENY", policy(Map.of("tests", "PASSED", "quality", "REJECTED", "security", "PASSED", "sbom", "PASSED"), digests));
        assertEquals("INDETERMINATE", policy(Map.of("tests", "PASSED", "quality", "INDETERMINATE", "security", "PASSED", "sbom", "PASSED"), digests));
        assertEquals("INDETERMINATE", policy(Map.of("tests", "PASSED", "quality", "PASSED", "security", "PASSED"), digests));
    }

    @Test
    void partialAndUnknownScannerEvidenceNeverPasses() {
        AssuranceTools.RawFinding unknown = new AssuranceTools.RawFinding("X-1", "UNMAPPED", "component",
                null, "rule", "proof", "recommendation");
        AssuranceTools.VulnerabilityResult partial = tools.normalizeFindings("1", "task-1", "attempt-1",
                "a".repeat(40), "Trivy", List.of(unknown), "evidence://task-1/attempt-1/trivy",
                "b".repeat(64), "PARTIAL", "workflow");
        assertEquals("INDETERMINATE", partial.verdict());
        assertEquals("UNKNOWN", partial.findings().getFirst().severity());
        assertThrows(IllegalArgumentException.class, () -> tools.normalizeFindings("1", "task-1", "attempt-1",
                "a".repeat(40), "UnknownScanner", List.of(), "evidence://task-1/attempt-1/security",
                "b".repeat(64), "COMPLETE", "workflow"));
        assertThrows(SecurityException.class, () -> tools.normalizeFindings("1", "task-1", "attempt-1",
                "a".repeat(40), "Trivy", List.of(), "evidence://task-1/attempt-1/security",
                "b".repeat(64), "COMPLETE", "security-agent"));
    }

    private String policy(Map<String, String> verdicts, Map<String, String> digests) {
        return tools.evaluatePolicy("1", "task-1", "attempt-1", verdicts, digests, "workflow").decision();
    }

    private AssuranceTools.QualityGateResult evaluate(String status, Integer exitCode, String evidence, String output) {
        return tools.evaluateQualityGate("1", "task-1", "attempt-1", "a".repeat(40), "SonarQube", "default",
                status, exitCode, evidence, output, "evidence://task-1/attempt-1/quality", "b".repeat(64),
                "workflow");
    }
}
