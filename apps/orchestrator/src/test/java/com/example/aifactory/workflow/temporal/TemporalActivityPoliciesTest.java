package com.example.aifactory.workflow.temporal;

import io.temporal.activity.ActivityOptions;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class TemporalActivityPoliciesTest {
    @Test
    void definesDistinctBoundedTimeoutAndRetryProfiles() {
        assertProfile(TemporalActivityPolicies.Kind.READ, Duration.ofSeconds(30), Duration.ofSeconds(30), 3,
                Duration.ofMillis(200), Duration.ofSeconds(2));
        assertProfile(TemporalActivityPolicies.Kind.LLM, Duration.ofMinutes(10), Duration.ofMinutes(2), 2,
                Duration.ofSeconds(2), Duration.ofSeconds(20));
        assertProfile(TemporalActivityPolicies.Kind.SANDBOX, Duration.ofMinutes(30), Duration.ofMinutes(5), 3,
                Duration.ofSeconds(2), Duration.ofSeconds(30));
        assertProfile(TemporalActivityPolicies.Kind.ASSURANCE, Duration.ofSeconds(90), Duration.ofMinutes(1), 3,
                Duration.ofMillis(500), Duration.ofSeconds(5));
        assertProfile(TemporalActivityPolicies.Kind.EVIDENCE, Duration.ofMinutes(2), Duration.ofMinutes(1), 3,
                Duration.ofMillis(500), Duration.ofSeconds(5));
        assertProfile(TemporalActivityPolicies.Kind.SCM, Duration.ofMinutes(4), Duration.ofMinutes(2), 2,
                Duration.ofSeconds(1), Duration.ofSeconds(10));
        assertProfile(TemporalActivityPolicies.Kind.A2A_CONTINUE, Duration.ofSeconds(45),
                Duration.ofSeconds(30), 1, Duration.ofMillis(250), Duration.ofMillis(250));
        assertProfile(TemporalActivityPolicies.Kind.A2A_AUTH, Duration.ofSeconds(50),
                Duration.ofSeconds(30), 1, Duration.ofMillis(250), Duration.ofMillis(250));
        assertThat(TemporalActivityPolicies.forKind(TemporalActivityPolicies.Kind.SANDBOX).getHeartbeatTimeout())
                .isEqualTo(Duration.ofSeconds(30));
        assertThat(Arrays.stream(TemporalActivityPolicies.Kind.values())
                .map(TemporalActivityPolicies::forKind)).allSatisfy(options -> {
                    assertThat(options.getStartToCloseTimeout()).isPositive();
                    assertThat(options.getScheduleToCloseTimeout()).isGreaterThan(options.getStartToCloseTimeout());
                });
    }

    private static void assertProfile(TemporalActivityPolicies.Kind kind, Duration timeout,
                                      Duration scheduleToStart, int attempts, Duration initial, Duration maximum) {
        ActivityOptions options = TemporalActivityPolicies.forKind(kind);
        assertThat(options.getStartToCloseTimeout()).isEqualTo(timeout);
        assertThat(options.getScheduleToCloseTimeout()).isGreaterThan(timeout);
        assertThat(options.getScheduleToStartTimeout()).isEqualTo(scheduleToStart);
        assertThat(options.getRetryOptions().getMaximumAttempts()).isEqualTo(attempts);
        assertThat(options.getRetryOptions().getInitialInterval()).isEqualTo(initial);
        assertThat(options.getRetryOptions().getMaximumInterval()).isEqualTo(maximum);
        assertThat(options.getRetryOptions().getBackoffCoefficient()).isEqualTo(2.0);
        assertThat(options.getRetryOptions().getDoNotRetry())
                .contains("INVALID_ARGUMENT", "PERMISSION_DENIED", "INCOMPATIBLE_SCHEMA", "POLICY_DENIED",
                        "BUSINESS_REJECTION", "CONTRACT_ERROR", "EFFECT_OUTCOME_UNKNOWN");
    }
}
