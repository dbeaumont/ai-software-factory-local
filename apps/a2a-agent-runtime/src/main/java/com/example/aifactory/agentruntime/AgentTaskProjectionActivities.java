package com.example.aifactory.agentruntime;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface AgentTaskProjectionActivities {
    @ActivityMethod
    void project(Projection projection);

    record Projection(String taskId, String state, String transitionId) {}
}
