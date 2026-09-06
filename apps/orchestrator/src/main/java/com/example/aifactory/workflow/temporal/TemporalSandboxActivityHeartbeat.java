package com.example.aifactory.workflow.temporal;

import com.example.aifactory.service.SandboxActivityHeartbeat;
import io.temporal.activity.Activity;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Bridges sandbox polling checkpoints to Temporal heartbeat details. */
@Component
public final class TemporalSandboxActivityHeartbeat implements SandboxActivityHeartbeat {
    @Override
    public Optional<Checkpoint> latest() {
        return Activity.getExecutionContext().getHeartbeatDetails(Checkpoint.class);
    }

    @Override
    public void record(Checkpoint checkpoint) {
        Activity.getExecutionContext().heartbeat(checkpoint);
    }
}
