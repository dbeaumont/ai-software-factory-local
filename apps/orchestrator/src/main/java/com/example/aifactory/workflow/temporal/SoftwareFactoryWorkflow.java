package com.example.aifactory.workflow.temporal;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import io.temporal.workflow.SignalMethod;

import java.util.List;

@WorkflowInterface
public interface SoftwareFactoryWorkflow {
    @WorkflowMethod(name = "SoftwareFactoryWorkflow")
    Result run(Request request);

    @SignalMethod(name = "approve")
    void approve(ApprovalSignal signal);

    record Request(String taskId, String attemptId, String sourceCommit, String requirement,
                   List<DelegationWorkflow.Request> delegations, ApprovalRequest approvalRequest) {
        public Request {
            delegations = delegations == null ? List.of() : List.copyOf(delegations);
        }

        public Request(String taskId, String attemptId, String sourceCommit, String requirement) {
            this(taskId, attemptId, sourceCommit, requirement, List.of(), null);
        }

        public Request(String taskId, String attemptId, String sourceCommit, String requirement,
                       List<DelegationWorkflow.Request> delegations) {
            this(taskId, attemptId, sourceCommit, requirement, delegations, null);
        }
    }

    record Result(String taskId, String attemptId, String sourceCommit, String status, List<String> chronology,
                  List<DelegationWorkflow.Result> delegations, String approvedManifestId, String approvedBy) {
        public Result {
            chronology = List.copyOf(chronology);
            delegations = List.copyOf(delegations);
        }
    }

    record ApprovalRequest(String manifestId, String uri, String digest) {}

    record ApprovalSignal(String taskId, String attemptId, String manifestId, String manifestDigest,
                          String decision, String approver, String decidedAt) {}
}
