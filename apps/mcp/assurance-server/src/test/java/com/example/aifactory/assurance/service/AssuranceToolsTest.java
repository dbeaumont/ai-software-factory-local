package com.example.aifactory.assurance.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.List;

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
                "b".repeat(64), "COMPLETE");

        assertEquals(List.of("CRITICAL", "HIGH"), result.findings().stream().map(AssuranceTools.NormalizedFinding::severity).toList());
        assertEquals("REJECTED", result.verdict());
        assertEquals(1, result.summary().get("critical"));
    }

    private AssuranceTools.QualityGateResult evaluate(String status, Integer exitCode, String evidence, String output) {
        return tools.evaluateQualityGate("1", "task-1", "attempt-1", "a".repeat(40), "SonarQube", "default",
                status, exitCode, evidence, output, "evidence://task-1/attempt-1/quality", "b".repeat(64));
    }
}
