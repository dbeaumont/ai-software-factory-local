package com.example.aifactory.workflow.temporal;

import io.temporal.activity.ActivityOptions;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class TemporalActivityPoliciesTest {
    @Test
    void definesDistinctBoundedTimeoutAndRetryProfiles() {
        assertProfile(TemporalActivityPolicies.Kind.READ, Duration.ofSeconds(30), 3);
        assertProfile(TemporalActivityPolicies.Kind.LLM, Duration.ofMinutes(10), 2);
        assertProfile(TemporalActivityPolicies.Kind.SANDBOX, Duration.ofMinutes(30), 2);
        assertProfile(TemporalActivityPolicies.Kind.ASSURANCE, Duration.ofSeconds(90), 3);
        assertProfile(TemporalActivityPolicies.Kind.EVIDENCE, Duration.ofMinutes(2), 3);
        assertProfile(TemporalActivityPolicies.Kind.SCM, Duration.ofMinutes(4), 2);
        assertThat(TemporalActivityPolicies.forKind(TemporalActivityPolicies.Kind.SANDBOX).getHeartbeatTimeout())
                .isEqualTo(Duration.ofSeconds(30));
        assertThat(Arrays.stream(TemporalActivityPolicies.Kind.values())
                .map(TemporalActivityPolicies::forKind).map(ActivityOptions::getStartToCloseTimeout)
                .distinct()).hasSize(TemporalActivityPolicies.Kind.values().length);
    }

    private static void assertProfile(TemporalActivityPolicies.Kind kind, Duration timeout, int attempts) {
        ActivityOptions options = TemporalActivityPolicies.forKind(kind);
        assertThat(options.getStartToCloseTimeout()).isEqualTo(timeout);
        assertThat(options.getScheduleToCloseTimeout()).isGreaterThan(timeout);
        assertThat(options.getRetryOptions().getMaximumAttempts()).isEqualTo(attempts);
        assertThat(options.getRetryOptions().getDoNotRetry())
                .contains("INVALID_ARGUMENT", "PERMISSION_DENIED", "INCOMPATIBLE_SCHEMA", "POLICY_DENIED");
    }
}
