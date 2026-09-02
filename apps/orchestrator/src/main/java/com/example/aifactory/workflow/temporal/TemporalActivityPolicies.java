package com.example.aifactory.workflow.temporal;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

/** Version 1 activity policies; workflow code selects a profile instead of constructing ad-hoc options. */
public final class TemporalActivityPolicies {
    private static final String[] NON_RETRYABLE = {
            "INVALID_ARGUMENT", "PERMISSION_DENIED", "INCOMPATIBLE_SCHEMA", "POLICY_DENIED"
    };
    private static final Map<Kind, ActivityOptions> OPTIONS = build();

    private TemporalActivityPolicies() {}

    public static ActivityOptions forKind(Kind kind) {
        ActivityOptions options = OPTIONS.get(kind);
        if (options == null) throw new IllegalArgumentException("Unknown activity policy");
        return options;
    }

    private static Map<Kind, ActivityOptions> build() {
        EnumMap<Kind, ActivityOptions> options = new EnumMap<>(Kind.class);
        options.put(Kind.READ, activity(Duration.ofMinutes(2), Duration.ofSeconds(30), null,
                retry(3, Duration.ofMillis(200), Duration.ofSeconds(2))));
        options.put(Kind.LLM, activity(Duration.ofMinutes(20), Duration.ofMinutes(10), null,
                retry(2, Duration.ofSeconds(2), Duration.ofSeconds(20))));
        options.put(Kind.SANDBOX, activity(Duration.ofMinutes(45), Duration.ofMinutes(30), Duration.ofSeconds(30),
                retry(2, Duration.ofSeconds(2), Duration.ofSeconds(30))));
        options.put(Kind.ASSURANCE, activity(Duration.ofMinutes(5), Duration.ofSeconds(90), null,
                retry(3, Duration.ofMillis(500), Duration.ofSeconds(5))));
        options.put(Kind.EVIDENCE, activity(Duration.ofMinutes(5), Duration.ofMinutes(2), null,
                retry(3, Duration.ofMillis(500), Duration.ofSeconds(5))));
        options.put(Kind.SCM, activity(Duration.ofMinutes(10), Duration.ofMinutes(4), null,
                retry(2, Duration.ofSeconds(1), Duration.ofSeconds(10))));
        return Map.copyOf(options);
    }

    private static ActivityOptions activity(Duration scheduleToClose, Duration startToClose,
                                            Duration heartbeat, RetryOptions retry) {
        ActivityOptions.Builder builder = ActivityOptions.newBuilder()
                .setScheduleToCloseTimeout(scheduleToClose)
                .setStartToCloseTimeout(startToClose)
                .setRetryOptions(retry);
        if (heartbeat != null) builder.setHeartbeatTimeout(heartbeat);
        return builder.build();
    }

    private static RetryOptions retry(int attempts, Duration initial, Duration maximum) {
        return RetryOptions.newBuilder().setMaximumAttempts(attempts).setInitialInterval(initial)
                .setMaximumInterval(maximum).setBackoffCoefficient(2.0)
                .setDoNotRetry(NON_RETRYABLE).build();
    }

    public enum Kind { READ, LLM, SANDBOX, ASSURANCE, EVIDENCE, SCM }
}
