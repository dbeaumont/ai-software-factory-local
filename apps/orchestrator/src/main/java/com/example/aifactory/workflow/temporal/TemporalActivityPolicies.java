package com.example.aifactory.workflow.temporal;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

/** Version 1 activity policies; workflow code selects a profile instead of constructing ad-hoc options. */
public final class TemporalActivityPolicies {
    private static final String[] NON_RETRYABLE = {
            "INVALID_ARGUMENT", "PERMISSION_DENIED", "INCOMPATIBLE_SCHEMA", "POLICY_DENIED",
            "BUSINESS_REJECTION", "CONTRACT_ERROR", "EFFECT_OUTCOME_UNKNOWN"
    };
    private static final Map<Kind, ActivityOptions> OPTIONS = build();

    private TemporalActivityPolicies() {}

    public static ActivityOptions forKind(Kind kind) {
        ActivityOptions options = OPTIONS.get(kind);
        if (options == null) throw new IllegalArgumentException("Unknown activity policy");
        return options;
    }

    public static ActivityOptions forKind(Kind kind, String taskQueue) {
        if (taskQueue == null || !taskQueue.matches("[a-z][a-z0-9-]{2,63}")) {
            throw new IllegalArgumentException("Temporal activity task queue is invalid");
        }
        return ActivityOptions.newBuilder(forKind(kind)).setTaskQueue(taskQueue).build();
    }

    private static Map<Kind, ActivityOptions> build() {
        EnumMap<Kind, ActivityOptions> options = new EnumMap<>(Kind.class);
        options.put(Kind.READ, activity(Duration.ofMinutes(2), Duration.ofSeconds(30), Duration.ofSeconds(30), null,
                retry(3, Duration.ofMillis(200), Duration.ofSeconds(2))));
        options.put(Kind.LLM, activity(Duration.ofMinutes(20), Duration.ofMinutes(10), Duration.ofMinutes(2), null,
                retry(2, Duration.ofSeconds(2), Duration.ofSeconds(20))));
        options.put(Kind.SANDBOX, activity(Duration.ofMinutes(45), Duration.ofMinutes(30), Duration.ofMinutes(5),
                Duration.ofSeconds(30),
                retry(3, Duration.ofSeconds(2), Duration.ofSeconds(30))));
        options.put(Kind.ASSURANCE, activity(Duration.ofMinutes(5), Duration.ofSeconds(90), Duration.ofMinutes(1), null,
                retry(3, Duration.ofMillis(500), Duration.ofSeconds(5))));
        options.put(Kind.EVIDENCE, activity(Duration.ofMinutes(5), Duration.ofMinutes(2), Duration.ofMinutes(1), null,
                retry(3, Duration.ofMillis(500), Duration.ofSeconds(5))));
        options.put(Kind.SCM, activity(Duration.ofMinutes(10), Duration.ofMinutes(4), Duration.ofMinutes(2), null,
                retry(2, Duration.ofSeconds(1), Duration.ofSeconds(10))));
        options.put(Kind.A2A_RESOLVE, activity(Duration.ofMinutes(1), Duration.ofSeconds(20),
                Duration.ofSeconds(20), null, retry(3, Duration.ofMillis(250), Duration.ofSeconds(2))));
        options.put(Kind.A2A_DISPATCH, activity(Duration.ofMinutes(2), Duration.ofSeconds(45),
                Duration.ofSeconds(30), null, retry(1, Duration.ofMillis(250), Duration.ofMillis(250))));
        options.put(Kind.A2A_GET, activity(Duration.ofMinutes(1), Duration.ofSeconds(20),
                Duration.ofSeconds(20), null, retry(3, Duration.ofMillis(500), Duration.ofSeconds(3))));
        options.put(Kind.A2A_CANCEL, activity(Duration.ofMinutes(2), Duration.ofSeconds(30),
                Duration.ofSeconds(30), null, retry(2, Duration.ofMillis(500), Duration.ofSeconds(2))));
        options.put(Kind.A2A_VALIDATE, activity(Duration.ofSeconds(30), Duration.ofSeconds(10),
                Duration.ofSeconds(10), null, retry(1, Duration.ofMillis(100), Duration.ofMillis(100))));
        options.put(Kind.A2A_RECONCILE, activity(Duration.ofMinutes(3), Duration.ofMinutes(1),
                Duration.ofSeconds(30), null, retry(3, Duration.ofSeconds(1), Duration.ofSeconds(5))));
        options.put(Kind.A2A_CONTINUE, activity(Duration.ofMinutes(2), Duration.ofSeconds(45),
                Duration.ofSeconds(30), null, retry(1, Duration.ofMillis(250), Duration.ofMillis(250))));
        options.put(Kind.A2A_AUTH, activity(Duration.ofMinutes(2), Duration.ofSeconds(50),
                Duration.ofSeconds(30), null, retry(1, Duration.ofMillis(250), Duration.ofMillis(250))));
        return Map.copyOf(options);
    }

    private static ActivityOptions activity(Duration scheduleToClose, Duration startToClose,
                                            Duration scheduleToStart, Duration heartbeat, RetryOptions retry) {
        ActivityOptions.Builder builder = ActivityOptions.newBuilder()
                .setScheduleToCloseTimeout(scheduleToClose)
                .setStartToCloseTimeout(startToClose)
                .setScheduleToStartTimeout(scheduleToStart)
                .setRetryOptions(retry);
        if (heartbeat != null) builder.setHeartbeatTimeout(heartbeat);
        return builder.build();
    }

    private static RetryOptions retry(int attempts, Duration initial, Duration maximum) {
        return RetryOptions.newBuilder().setMaximumAttempts(attempts).setInitialInterval(initial)
                .setMaximumInterval(maximum).setBackoffCoefficient(2.0)
                .setDoNotRetry(NON_RETRYABLE).build();
    }

    public enum Kind {
        READ, LLM, SANDBOX, ASSURANCE, EVIDENCE, SCM,
        A2A_RESOLVE, A2A_DISPATCH, A2A_GET, A2A_CANCEL, A2A_VALIDATE, A2A_RECONCILE, A2A_CONTINUE, A2A_AUTH
    }
}
