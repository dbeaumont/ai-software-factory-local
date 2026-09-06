package com.example.aifactory.workflow.temporal;

import com.example.aifactory.service.McpInvocationException;
import io.temporal.client.ActivityWorkerShutdownException;
import io.temporal.failure.ApplicationFailure;
import io.temporal.failure.TimeoutFailure;

import java.util.Locale;
import java.util.concurrent.TimeoutException;

/** Converts infrastructure exceptions into stable Temporal failure types without leaking unsafe details. */
public final class TemporalFailureClassifier {
    private TemporalFailureClassifier() {}

    public static Classification classify(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof ApplicationFailure application) {
                try {
                    return new Classification(Type.valueOf(application.getType()), !application.isNonRetryable());
                } catch (IllegalArgumentException ignored) {
                    return new Classification(Type.CONTRACT_ERROR, false);
                }
            }
            if (current instanceof EffectOutcomeUnknownException) {
                return new Classification(Type.EFFECT_OUTCOME_UNKNOWN, false);
            }
            if (current instanceof BusinessRejectionException || current instanceof SecurityException) {
                return new Classification(Type.BUSINESS_REJECTION, false);
            }
            if (current instanceof IllegalArgumentException) {
                return new Classification(Type.CONTRACT_ERROR, false);
            }
            if (current instanceof TimeoutFailure || current instanceof TimeoutException) {
                return new Classification(Type.TIMEOUT, true);
            }
            if (current instanceof McpInvocationException mcp) {
                String code = mcp.code().toUpperCase(Locale.ROOT);
                if (code.contains("SATURAT") || code.contains("CAPACITY") || code.contains("RATE_LIMIT")) {
                    return new Classification(Type.SATURATION, true);
                }
                if (code.contains("TIMEOUT") || code.contains("DEADLINE")) {
                    return new Classification(Type.TIMEOUT, true);
                }
                return new Classification(Type.DEPENDENCY_UNAVAILABLE, mcp.retryable());
            }
            current = current.getCause();
        }
        return new Classification(Type.DEPENDENCY_UNAVAILABLE, true);
    }

    public static ApplicationFailure toApplicationFailure(Throwable failure) {
        if (failure instanceof ActivityWorkerShutdownException shutdown) throw shutdown;
        if (failure instanceof ApplicationFailure application) return application;
        Classification classification = classify(failure);
        String message = "Activity failed: " + classification.type().name().toLowerCase(Locale.ROOT);
        return classification.retryable()
                ? ApplicationFailure.newFailureWithCause(message, classification.type().name(), failure)
                : ApplicationFailure.newNonRetryableFailureWithCause(message, classification.type().name(), failure);
    }

    public enum Type {
        BUSINESS_REJECTION,
        CONTRACT_ERROR,
        SATURATION,
        TIMEOUT,
        DEPENDENCY_UNAVAILABLE,
        EFFECT_OUTCOME_UNKNOWN
    }

    public record Classification(Type type, boolean retryable) {
        public boolean requiresReconciliation() {
            return type == Type.EFFECT_OUTCOME_UNKNOWN;
        }
    }

    public static final class BusinessRejectionException extends IllegalStateException {
        public BusinessRejectionException(String safeMessage) { super(safeMessage); }
    }

    public static final class EffectOutcomeUnknownException extends IllegalStateException {
        public EffectOutcomeUnknownException(String safeMessage, Throwable cause) { super(safeMessage, cause); }
    }
}
