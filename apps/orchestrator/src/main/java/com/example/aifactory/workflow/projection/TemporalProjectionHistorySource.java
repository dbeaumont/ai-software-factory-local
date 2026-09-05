package com.example.aifactory.workflow.projection;

import com.example.aifactory.workflow.temporal.SoftwareFactoryWorkflow;
import com.google.protobuf.Timestamp;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.common.converter.DataConverter;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.LinkedHashMap;
import java.util.Map;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import com.example.aifactory.service.PipelineStepContracts;
import com.example.aifactory.workflow.EvidenceRepository;
import com.example.aifactory.workflow.temporal.PipelineExecutionActivities;
import com.example.aifactory.workflow.temporal.SourceResolutionActivities;

/** Converts the immutable Temporal event history into the normalized facts used by projection rebuilding. */
@Component
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
        WorkflowExecutionHistory history;
        try {
            history = client.fetchHistory(workflowId, runId);
        } catch (WorkflowNotFoundException notFound) {
            throw new ProjectionHistoryUnavailableException(workflowId, runId, notFound);
        } catch (StatusRuntimeException status) {
            if (status.getStatus().getCode() == Status.Code.NOT_FOUND) {
                throw new ProjectionHistoryUnavailableException(workflowId, runId, status);
            }
            throw status;
        }
        List<HistoryEvent> events = history.getEvents();
        HistoryEvent started = events.stream()
                .filter(HistoryEvent::hasWorkflowExecutionStartedEventAttributes)
                .findFirst().orElseThrow(() -> new IllegalStateException("Temporal history has no start event"));
        SoftwareFactoryWorkflow.Request request = converter.fromPayloads(0,
                Optional.of(started.getWorkflowExecutionStartedEventAttributes().getInput()),
                SoftwareFactoryWorkflow.Request.class, SoftwareFactoryWorkflow.Request.class);

        Map<Long, String> activityTypes = new LinkedHashMap<>();
        events.stream().filter(HistoryEvent::hasActivityTaskScheduledEventAttributes).forEach(event ->
                activityTypes.put(event.getEventId(), event.getActivityTaskScheduledEventAttributes()
                        .getActivityType().getName()));
        SourceResolutionActivities.Result source = completedActivity(events, activityTypes,
                "ResolveAndAttestSource", SourceResolutionActivities.Result.class);
        if (source != null) request = request.withResolvedSource(source.sourceCommit());
        List<EvidencePointer> evidence = evidence(events, activityTypes);

        HistoryEvent completed = events.stream()
                .filter(HistoryEvent::hasWorkflowExecutionCompletedEventAttributes).findFirst().orElse(null);
        SoftwareFactoryWorkflow.Result result = completed == null ? null : converter.fromPayloads(0,
                Optional.of(completed.getWorkflowExecutionCompletedEventAttributes().getResult()),
                SoftwareFactoryWorkflow.Result.class, SoftwareFactoryWorkflow.Result.class);
        HistoryEvent last = events.getLast();
        return new History(workflowId, runId, instant(started.getEventTime()),
                terminal(last) ? instant(last.getEventTime()) : null, terminalStatus(last), request, result,
                evidence);
    }

    private List<EvidencePointer> evidence(List<HistoryEvent> events, Map<Long, String> activityTypes) {
        Map<String, EvidencePointer> pointers = new LinkedHashMap<>();
        for (HistoryEvent event : events) {
            if (!event.hasActivityTaskCompletedEventAttributes()) continue;
            var completed = event.getActivityTaskCompletedEventAttributes();
            String type = activityTypes.get(completed.getScheduledEventId());
            if (type == null || !completed.hasResult()) continue;
            if (java.util.Set.of("ExecutePipelineStep", "GeneratePatchCandidate", "RepairPatchCandidate")
                    .contains(type)) {
                PipelineStepContracts.Result value = decode(completed.getResult(), PipelineStepContracts.Result.class);
                add(pointers, value.artifacts());
            } else if ("ValidatePatchCandidate".equals(type)) {
                PipelineExecutionActivities.PatchValidationResult value = decode(
                        completed.getResult(), PipelineExecutionActivities.PatchValidationResult.class);
                add(pointers, value.result().artifacts());
                if (value.validationError() != null) add(pointers, Map.of("validation", value.validationError()));
            } else if ("CreatePipelineApprovalManifest".equals(type)) {
                EvidenceRepository.StoredManifest manifest = decode(
                        completed.getResult(), EvidenceRepository.StoredManifest.class);
                pointers.put(manifest.uri(), new EvidencePointer(manifest.uri(), manifest.digest()));
            }
        }
        return List.copyOf(pointers.values());
    }

    private static void add(Map<String, EvidencePointer> pointers,
                            Map<String, PipelineStepContracts.ArtifactReference> artifacts) {
        artifacts.values().forEach(artifact -> pointers.put(artifact.uri(),
                new EvidencePointer(artifact.uri(), artifact.digest())));
    }

    private <T> T completedActivity(List<HistoryEvent> events, Map<Long, String> activityTypes,
                                    String activityType, Class<T> resultType) {
        return events.stream().filter(HistoryEvent::hasActivityTaskCompletedEventAttributes)
                .filter(event -> activityType.equals(activityTypes.get(
                        event.getActivityTaskCompletedEventAttributes().getScheduledEventId())))
                .filter(event -> event.getActivityTaskCompletedEventAttributes().hasResult())
                .map(event -> decode(event.getActivityTaskCompletedEventAttributes().getResult(), resultType))
                .findFirst().orElse(null);
    }

    private <T> T decode(io.temporal.api.common.v1.Payloads payloads, Class<T> type) {
        return converter.fromPayloads(0, Optional.of(payloads), type, type);
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
