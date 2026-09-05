package com.example.aifactory.workflow.projection;

import com.example.aifactory.workflow.temporal.SoftwareFactoryWorkflow;

import java.time.Instant;
import java.util.List;

/** Reads the durable facts needed to rebuild a projection from a Temporal execution history. */
public interface ProjectionHistorySource {
    History read(String workflowId, String runId);

    record History(String workflowId, String runId, Instant startedAt, Instant completedAt,
                   String terminalStatus, SoftwareFactoryWorkflow.Request request,
                   SoftwareFactoryWorkflow.Result result, List<EvidencePointer> evidence) {
        public History {
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
        }

        public History(String workflowId, String runId, Instant startedAt, Instant completedAt,
                       String terminalStatus, SoftwareFactoryWorkflow.Request request,
                       SoftwareFactoryWorkflow.Result result) {
            this(workflowId, runId, startedAt, completedAt, terminalStatus, request, result, List.of());
        }
    }

    record EvidencePointer(String uri, String digest) {}
}
