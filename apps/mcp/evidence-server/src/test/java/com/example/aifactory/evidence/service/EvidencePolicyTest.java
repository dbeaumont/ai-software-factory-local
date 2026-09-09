package com.example.aifactory.evidence.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EvidencePolicyTest {
    private final EvidencePolicy policy = new EvidencePolicy();

    @Test
    void allowsEveryNativeHierarchicalWorkflowReadPurpose() {
        List.of("hierarchical-specialist-result", "prepare-developer-tasks", "prepare-short-developer-task",
                "accept-developer-patch", "project-developer-patch", "prepare-native-patch-repair",
                "accept-native-patch-repair", "project-native-patch-repair")
                .forEach(purpose -> assertDoesNotThrow(
                        () -> policy.requireRead("agent-result", "workflow", purpose)));
    }

    @Test
    void keepsUnknownWorkflowReadPurposesFailClosed() {
        assertThrows(SecurityException.class,
                () -> policy.requireRead("agent-result", "workflow", "unregistered-purpose"));
    }
}
