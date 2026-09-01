package com.example.aifactory.assurance.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AssuranceToolsTest {
    private final AssuranceTools tools = new AssuranceTools();

    @Test
    void classifiesKnownPassFailureAndUnknownOutput() {
        assertEquals("PASSED", evaluate("SUCCEEDED", 0, "COMPLETE", "QUALITY GATE STATUS: PASSED").verdict());
        assertEquals("REJECTED", evaluate("SUCCEEDED", 1, "COMPLETE", "QUALITY GATE STATUS: FAILED").verdict());
        assertEquals("INDETERMINATE", evaluate("SUCCEEDED", 0, "COMPLETE", "ANALYSIS SUCCESSFUL").verdict());
        assertEquals("INDETERMINATE", evaluate("TIMED_OUT", null, "PARTIAL", "QUALITY GATE STATUS: PASSED").verdict());
    }

    private AssuranceTools.QualityGateResult evaluate(String status, Integer exitCode, String evidence, String output) {
        return tools.evaluateQualityGate("1", "task-1", "attempt-1", "a".repeat(40), "SonarQube", "default",
                status, exitCode, evidence, output, "evidence://task-1/attempt-1/quality", "b".repeat(64));
    }
}
