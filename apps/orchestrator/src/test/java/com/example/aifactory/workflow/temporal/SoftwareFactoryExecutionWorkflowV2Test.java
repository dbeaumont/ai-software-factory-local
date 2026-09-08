package com.example.aifactory.workflow.temporal;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SoftwareFactoryExecutionWorkflowV2Test {
    @Test
    void keepsExecutionModeOutOfThePersistedV2Contract() {
        assertThat(Arrays.stream(SoftwareFactoryExecutionWorkflowV2.Request.class.getRecordComponents())
                .map(RecordComponent::getName))
                .doesNotContain("executionMode", "requestedMode", "effectiveMode");
    }

    @Test
    void makesHierarchicalExecutionImplicitAtTheV1CompatibilityBoundary() {
        var request = new SoftwareFactoryExecutionWorkflowV2.Request(
                "task-1", "attempt-1", "customer-api", "UNRESOLVED", "requirement",
                new SoftwareFactoryWorkflow.SourceLocation("http://gitea/repo.git", "main", "context", Map.of()),
                null);

        assertThat(request.requirementDigest()).matches("[0-9a-f]{64}");
        assertThat(request.hierarchicalRequest().executionMode())
                .isEqualTo(SoftwareFactoryWorkflow.WorkflowExecutionMode.HIERARCHICAL_ACTIVE);
    }
}
