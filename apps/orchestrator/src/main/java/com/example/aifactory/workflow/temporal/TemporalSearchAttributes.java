package com.example.aifactory.workflow.temporal;

import io.temporal.common.SearchAttributeKey;
import io.temporal.common.SearchAttributes;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Versioned, low-cardinality-safe index contract for local Temporal visibility. */
final class TemporalSearchAttributes {
    static final SearchAttributeKey<String> TASK_ID = SearchAttributeKey.forKeyword("AiFactoryTaskId");
    static final SearchAttributeKey<String> ATTEMPT_ID = SearchAttributeKey.forKeyword("AiFactoryAttemptId");
    static final SearchAttributeKey<String> REPOSITORY_ID = SearchAttributeKey.forKeyword("AiFactoryRepositoryId");
    static final SearchAttributeKey<String> EXECUTION_MODE = SearchAttributeKey.forKeyword("AiFactoryExecutionMode");

    private static final Set<SearchAttributeKey<?>> V1_ALLOWED = Set.of(
            TASK_ID, ATTEMPT_ID, REPOSITORY_ID, EXECUTION_MODE);
    private static final Set<SearchAttributeKey<?>> V2_ALLOWED = Set.of(TASK_ID, ATTEMPT_ID, REPOSITORY_ID);
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final Set<String> EXECUTION_MODES = Set.of("PIPELINE", "DELEGATION");

    private TemporalSearchAttributes() {}

    static SearchAttributes forRequest(SoftwareFactoryWorkflow.Request request) {
        if (request == null) throw rejected();
        SearchAttributes attributes = SearchAttributes.newBuilder()
                .set(TASK_ID, request.taskId())
                .set(ATTEMPT_ID, request.attemptId())
                .set(REPOSITORY_ID, request.repositoryId())
                .set(EXECUTION_MODE, request.executionMode().name())
                .build();
        requireSafe(attributes);
        return attributes;
    }

    static SearchAttributes forV2Request(SoftwareFactoryExecutionWorkflowV2.Request request) {
        if (request == null) throw rejected();
        SearchAttributes attributes = SearchAttributes.newBuilder()
                .set(TASK_ID, request.taskId())
                .set(ATTEMPT_ID, request.attemptId())
                .set(REPOSITORY_ID, request.repositoryId())
                .build();
        requireSafe(attributes);
        return attributes;
    }

    static void requireSafe(SearchAttributes attributes) {
        if (attributes == null) throw rejected();
        Map<SearchAttributeKey<?>, Object> values = attributes.getUntypedValues();
        if (!values.keySet().equals(V1_ALLOWED) && !values.keySet().equals(V2_ALLOWED)) throw rejected();
        requireIdentifier(attributes.get(TASK_ID));
        requireIdentifier(attributes.get(ATTEMPT_ID));
        requireIdentifier(attributes.get(REPOSITORY_ID));
        if (values.keySet().equals(V1_ALLOWED) && !EXECUTION_MODES.contains(attributes.get(EXECUTION_MODE))) {
            throw rejected();
        }
    }

    private static void requireIdentifier(String value) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) throw rejected();
    }

    private static SecurityException rejected() {
        return new SecurityException("Temporal search attributes violate the indexed-data policy");
    }
}
