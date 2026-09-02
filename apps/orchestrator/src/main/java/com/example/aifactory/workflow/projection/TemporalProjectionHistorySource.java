package com.example.aifactory.workflow.projection;

import com.example.aifactory.workflow.temporal.SoftwareFactoryWorkflow;
import com.google.protobuf.Timestamp;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.client.WorkflowClient;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.common.converter.DataConverter;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Converts the immutable Temporal event history into the normalized facts used by projection rebuilding. */
public final class TemporalProjectionHistorySource implements ProjectionHistorySource {
    private final WorkflowClient client;
    private final DataConverter converter;

    public TemporalProjectionHistorySource(WorkflowClient client) {
        this(client, client.getOptions().getDataConverter());
    }

    TemporalProjectionHistorySource(WorkflowClient client, DataConverter converter) {
        this.client = client;
        this.converter = converter;
    }

    @Override
    public History read(String workflowId, String runId) {
        WorkflowExecutionHistory history = client.fetchHistory(workflowId, runId);
        List<HistoryEvent> events = history.getEvents();
        HistoryEvent started = events.stream()
                .filter(HistoryEvent::hasWorkflowExecutionStartedEventAttributes)
                .findFirst().orElseThrow(() -> new IllegalStateException("Temporal history has no start event"));
        SoftwareFactoryWorkflow.Request request = converter.fromPayloads(0,
                Optional.of(started.getWorkflowExecutionStartedEventAttributes().getInput()),
                SoftwareFactoryWorkflow.Request.class, SoftwareFactoryWorkflow.Request.class);

        HistoryEvent completed = events.stream()
                .filter(HistoryEvent::hasWorkflowExecutionCompletedEventAttributes).findFirst().orElse(null);
        SoftwareFactoryWorkflow.Result result = completed == null ? null : converter.fromPayloads(0,
                Optional.of(completed.getWorkflowExecutionCompletedEventAttributes().getResult()),
                SoftwareFactoryWorkflow.Result.class, SoftwareFactoryWorkflow.Result.class);
        HistoryEvent last = events.getLast();
        return new History(workflowId, runId, instant(started.getEventTime()),
                terminal(last) ? instant(last.getEventTime()) : null, terminalStatus(last), request, result);
    }

    private static boolean terminal(HistoryEvent event) {
        return event.hasWorkflowExecutionCompletedEventAttributes()
                || event.hasWorkflowExecutionFailedEventAttributes()
                || event.hasWorkflowExecutionCanceledEventAttributes()
                || event.hasWorkflowExecutionTimedOutEventAttributes()
                || event.hasWorkflowExecutionTerminatedEventAttributes()
                || event.hasWorkflowExecutionContinuedAsNewEventAttributes();
    }

    private static String terminalStatus(HistoryEvent event) {
        if (event.hasWorkflowExecutionCompletedEventAttributes()) return "COMPLETED";
        if (event.hasWorkflowExecutionFailedEventAttributes()) return "FAILED";
        if (event.hasWorkflowExecutionCanceledEventAttributes()) return "CANCELLED";
        if (event.hasWorkflowExecutionTimedOutEventAttributes()) return "TIMED_OUT";
        if (event.hasWorkflowExecutionTerminatedEventAttributes()) return "TERMINATED";
        if (event.hasWorkflowExecutionContinuedAsNewEventAttributes()) return "CONTINUED_AS_NEW";
        return "RUNNING";
    }

    private static Instant instant(Timestamp timestamp) {
        return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
    }
}
