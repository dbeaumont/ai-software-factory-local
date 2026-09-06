package com.example.aifactory.a2a;

import io.temporal.client.WorkflowClient;
import org.erdtman.jcs.JsonCanonicalizer;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

@Component
public final class TemporalA2aNotificationReceiver implements A2aNotificationReceiver {
    static final String SIGNAL_NAME = "a2aTaskUpdate";
    private final A2aTaskAssociationStore associations;
    private final A2aNotificationInbox inbox;
    private final WorkflowClient temporal;
    private final ObjectMapper mapper;

    public TemporalA2aNotificationReceiver(A2aTaskAssociationStore associations, A2aNotificationInbox inbox,
                                           WorkflowClient temporal, ObjectMapper mapper) {
        this.associations = associations;
        this.inbox = inbox;
        this.temporal = temporal;
        this.mapper = mapper;
    }

    @Override
    public CompletionStage<Void> receive(A2aContracts.Notification notification) {
        try {
            A2aTaskAssociationStore.Association association = associations.findByA2aTaskId(
                            notification.agentRole(), notification.taskId())
                    .orElseThrow(() -> new SecurityException("Unknown A2A notification task"));
            validate(association, notification);
            byte[] canonical = new JsonCanonicalizer(mapper.writeValueAsBytes(notification)).getEncodedUTF8();
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
            String payload = mapper.writeValueAsString(notification);
            A2aNotificationInbox.Admission admission = inbox.admit(association, notification, digest, payload);
            if (admission.shouldSignal()) {
                temporal.newUntypedWorkflowStub(association.workflowId(),
                                java.util.Optional.of(association.workflowRunId()), java.util.Optional.empty())
                        .signal(SIGNAL_NAME, notification);
                inbox.markSignalled(notification.agentRole(), notification.taskId(), notification.sequence(),
                        Instant.now());
            }
            return CompletableFuture.completedFuture(null);
        } catch (Exception failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private static void validate(A2aTaskAssociationStore.Association association,
                                 A2aContracts.Notification notification) {
        if (!association.a2aContextId().equals(notification.contextId()) || notification.sequence() < 0) {
            throw new SecurityException("A2A notification correlation mismatch");
        }
        for (A2aContracts.Artifact artifact : notification.artifacts()) {
            for (A2aContracts.Part part : artifact.parts()) {
                Object digest = part.data().get("digest");
                if (digest != null && (!(digest instanceof String value) || !value.matches("[0-9a-f]{64}"))) {
                    throw new SecurityException("A2A notification artifact digest is invalid");
                }
            }
        }
    }
}
