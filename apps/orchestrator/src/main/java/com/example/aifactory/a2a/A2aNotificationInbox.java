package com.example.aifactory.a2a;

import java.time.Instant;

/** Durable idempotency inbox for agent push notifications. */
public interface A2aNotificationInbox {
    Admission admit(A2aTaskAssociationStore.Association association, A2aContracts.Notification notification,
                    String payloadDigest, String payloadJson);
    void markSignalled(String agentRole, String taskId, long sequence, Instant signalledAt);

    record Admission(boolean shouldSignal) {}
}
