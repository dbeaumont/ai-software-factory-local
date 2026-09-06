package com.example.aifactory.workflow.temporal;

import com.example.aifactory.service.McpInvocationException;
import io.temporal.client.ActivityWorkerShutdownException;
import io.temporal.failure.ApplicationFailure;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TemporalFailureClassifierTest {
    @Test
    void classifiesBusinessContractCapacityTimeoutDependencyAndUnknownEffectFailures() {
        assertClassification(new TemporalFailureClassifier.BusinessRejectionException("gate refused"),
                TemporalFailureClassifier.Type.BUSINESS_REJECTION, false, false);
        assertClassification(new IllegalArgumentException("bad schema"),
                TemporalFailureClassifier.Type.CONTRACT_ERROR, false, false);
        assertClassification(new McpInvocationException("SATURATED", true, "full"),
                TemporalFailureClassifier.Type.SATURATION, true, false);
        assertClassification(new TimeoutException("late"),
                TemporalFailureClassifier.Type.TIMEOUT, true, false);
        assertClassification(new McpInvocationException("DEPENDENCY_UNAVAILABLE", true, "down"),
                TemporalFailureClassifier.Type.DEPENDENCY_UNAVAILABLE, true, false);
        assertClassification(new TemporalFailureClassifier.EffectOutcomeUnknownException("unknown", null),
                TemporalFailureClassifier.Type.EFFECT_OUTCOME_UNKNOWN, false, true);
    }

    @Test
    void convertsCategoriesToStableTemporalTypesAndRetryFlags() {
        ApplicationFailure contract = TemporalFailureClassifier.toApplicationFailure(
                new IllegalArgumentException("unsafe payload detail"));
        ApplicationFailure dependency = TemporalFailureClassifier.toApplicationFailure(
                new McpInvocationException("DEPENDENCY_UNAVAILABLE", true, "internal endpoint"));

        assertThat(contract.getType()).isEqualTo("CONTRACT_ERROR");
        assertThat(contract.isNonRetryable()).isTrue();
        assertThat(contract.getMessage()).doesNotContain("unsafe payload detail");
        assertThat(dependency.getType()).isEqualTo("DEPENDENCY_UNAVAILABLE");
        assertThat(dependency.isNonRetryable()).isFalse();
    }

    @Test
    void propagatesWorkerShutdownWithoutMisclassifyingItAsADependencyFailure() {
        ActivityWorkerShutdownException shutdown = new ActivityWorkerShutdownException();

        assertThatThrownBy(() -> TemporalFailureClassifier.toApplicationFailure(shutdown))
                .isSameAs(shutdown);
    }

    private static void assertClassification(Throwable failure, TemporalFailureClassifier.Type type,
                                             boolean retryable, boolean reconcile) {
        var classification = TemporalFailureClassifier.classify(failure);
        assertThat(classification.type()).isEqualTo(type);
        assertThat(classification.retryable()).isEqualTo(retryable);
        assertThat(classification.requiresReconciliation()).isEqualTo(reconcile);
    }
}
