package com.example.aifactory.workflow.temporal;

import io.temporal.workflow.Workflow;

/** Keeps polling workflows below conservative history limits without changing logical task identity. */
final class A2aWorkflowHistoryGuard {
    static final int MAX_HISTORY_EVENTS = 250;
    static final long MAX_HISTORY_BYTES = 1_048_576;

    private A2aWorkflowHistoryGuard() {}

    static void delegation(DelegationWorkflow.Request request) {
        if (exceeds(Workflow.getInfo().getHistoryLength(), Workflow.getInfo().getHistorySize(),
                Workflow.getInfo().isContinueAsNewSuggested())) {
            Workflow.newContinueAsNewStub(DelegationWorkflow.class).run(request);
        }
    }

    static void independentReview(IndependentReviewWorkflow.Request request) {
        if (exceeds(Workflow.getInfo().getHistoryLength(), Workflow.getInfo().getHistorySize(),
                Workflow.getInfo().isContinueAsNewSuggested())) {
            Workflow.newContinueAsNewStub(IndependentReviewWorkflow.class).run(request);
        }
    }

    static boolean exceeds(long historyEvents, long historyBytes, boolean serverSuggested) {
        return serverSuggested || historyEvents >= MAX_HISTORY_EVENTS || historyBytes >= MAX_HISTORY_BYTES;
    }
}
