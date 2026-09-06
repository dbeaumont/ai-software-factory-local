package com.example.aifactory.workflow.temporal;

import com.example.aifactory.service.SandboxActivityHeartbeat;
import io.temporal.activity.ActivityExecutionContext;
import io.temporal.activity.ActivityInfo;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TemporalSandboxActivityHeartbeatTest {
    private static final SandboxActivityHeartbeat.Checkpoint CHECKPOINT =
            new SandboxActivityHeartbeat.Checkpoint(
                    "effect-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                    "run-quality", "0123456789abcdef0123456789abcdef");

    @Test
    void recordsAndRestoresCheckpointsWhenTheActivityHasAHeartbeatTimeout() {
        ActivityExecutionContext context = context(Duration.ofSeconds(30));
        when(context.getHeartbeatDetails(SandboxActivityHeartbeat.Checkpoint.class))
                .thenReturn(Optional.of(CHECKPOINT));
        TemporalSandboxActivityHeartbeat heartbeat = new TemporalSandboxActivityHeartbeat(() -> context);

        assertThat(heartbeat.latest()).contains(CHECKPOINT);
        heartbeat.record(CHECKPOINT);

        verify(context).heartbeat(CHECKPOINT);
    }

    @Test
    void ignoresHeartbeatsForShortActivitiesWithoutAHeartbeatTimeout() {
        ActivityExecutionContext context = context(Duration.ZERO);
        TemporalSandboxActivityHeartbeat heartbeat = new TemporalSandboxActivityHeartbeat(() -> context);

        assertThat(heartbeat.latest()).isEmpty();
        heartbeat.record(CHECKPOINT);

        verify(context, never()).getHeartbeatDetails(SandboxActivityHeartbeat.Checkpoint.class);
        verify(context, never()).heartbeat(CHECKPOINT);
    }

    private static ActivityExecutionContext context(Duration heartbeatTimeout) {
        ActivityExecutionContext context = mock(ActivityExecutionContext.class);
        ActivityInfo info = mock(ActivityInfo.class);
        when(context.getInfo()).thenReturn(info);
        when(info.getHeartbeatTimeout()).thenReturn(heartbeatTimeout);
        return context;
    }
}
