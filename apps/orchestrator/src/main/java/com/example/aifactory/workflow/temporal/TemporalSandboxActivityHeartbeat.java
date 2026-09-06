package com.example.aifactory.workflow.temporal;

import com.example.aifactory.service.SandboxActivityHeartbeat;
import io.temporal.activity.Activity;
import io.temporal.activity.ActivityExecutionContext;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

/** Bridges sandbox polling checkpoints to Temporal heartbeat details. */
@Component
public final class TemporalSandboxActivityHeartbeat implements SandboxActivityHeartbeat {
    private final Supplier<ActivityExecutionContext> contexts;

    public TemporalSandboxActivityHeartbeat() {
        this(Activity::getExecutionContext);
    }

    TemporalSandboxActivityHeartbeat(Supplier<ActivityExecutionContext> contexts) {
        this.contexts = contexts;
    }

    @Override
    public Optional<Checkpoint> latest() {
        ActivityExecutionContext context = contexts.get();
        return heartbeatsEnabled(context)
                ? context.getHeartbeatDetails(Checkpoint.class)
                : Optional.empty();
    }

    @Override
    public void record(Checkpoint checkpoint) {
        ActivityExecutionContext context = contexts.get();
        if (heartbeatsEnabled(context)) context.heartbeat(checkpoint);
    }

    private static boolean heartbeatsEnabled(ActivityExecutionContext context) {
        Duration timeout = context.getInfo().getHeartbeatTimeout();
        return timeout != null && !timeout.isZero() && !timeout.isNegative();
    }
}
