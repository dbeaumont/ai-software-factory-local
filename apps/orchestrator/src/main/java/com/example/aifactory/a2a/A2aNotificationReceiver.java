package com.example.aifactory.a2a;

import java.util.concurrent.CompletionStage;

/** Accepts authenticated task updates before they are signalled to the owning Temporal workflow. */
public interface A2aNotificationReceiver {

    CompletionStage<Void> receive(A2aContracts.Notification notification);
}
