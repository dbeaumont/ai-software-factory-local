package com.example.aifactory.workflow.temporal;

import io.temporal.common.SearchAttributeKey;
import io.temporal.common.SearchAttributes;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TemporalSearchAttributesTest {
    @Test
    void acceptsTheCompleteVersionedSchema() {
        var request = new SoftwareFactoryWorkflow.Request(
                "task-123", "pipeline-2", "customer-api", "requirement");

        assertThatCode(() -> TemporalSearchAttributes.requireSafe(
                TemporalSearchAttributes.forRequest(request))).doesNotThrowAnyException();
    }

    @Test
    void acceptsV2WithoutTheLegacyExecutionModeAttribute() {
        var request = new SoftwareFactoryExecutionWorkflowV2.Request(
                "task-123", "attempt-2", "customer-api", "UNRESOLVED", "requirement", null, null);

        assertThatCode(() -> TemporalSearchAttributes.requireSafe(
                TemporalSearchAttributes.forV2Request(request))).doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingAdditionalAndUnboundedValues() {
        assertThatThrownBy(() -> TemporalSearchAttributes.requireSafe(SearchAttributes.newBuilder()
                .set(TemporalSearchAttributes.TASK_ID, "task-1").build()))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> TemporalSearchAttributes.requireSafe(SearchAttributes.newBuilder()
                .set(TemporalSearchAttributes.TASK_ID, "task-1")
                .set(TemporalSearchAttributes.ATTEMPT_ID, "pipeline-1")
                .set(TemporalSearchAttributes.REPOSITORY_ID, "customer-api")
                .set(TemporalSearchAttributes.EXECUTION_MODE, "PIPELINE")
                .set(SearchAttributeKey.forKeyword("SecretLookup"), "forbidden")
                .build())).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> TemporalSearchAttributes.forRequest(new SoftwareFactoryWorkflow.Request(
                "x".repeat(129), "pipeline-1", "customer-api", "requirement")))
                .isInstanceOf(SecurityException.class);
    }
}
