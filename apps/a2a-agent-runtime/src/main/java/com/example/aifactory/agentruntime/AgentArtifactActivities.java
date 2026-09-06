package com.example.aifactory.agentruntime;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.util.Set;

@ActivityInterface
public interface AgentArtifactActivities {
    @ActivityMethod
    ArtifactReference publish(PublishCommand command);

    record PublishCommand(String taskId, String attemptId, String role, String outputContract,
                          Set<String> allowedReferenceIds, String contentBase64, String digest) {
        public PublishCommand {
            allowedReferenceIds = allowedReferenceIds == null ? Set.of() : Set.copyOf(allowedReferenceIds);
        }
    }

    record ArtifactReference(String artifactId, String uri, String digest) {}
}
