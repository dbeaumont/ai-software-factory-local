package com.example.aifactory.agentruntime;

public final class AgentArtifactActivitiesImpl implements AgentArtifactActivities {
    private final EvidenceArtifactPublisher publisher;

    public AgentArtifactActivitiesImpl(EvidenceArtifactPublisher publisher) { this.publisher = publisher; }

    @Override
    public ArtifactReference publish(PublishCommand command) { return publisher.publish(command); }
}
