package com.example.aifactory.workflow.temporal;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

import java.util.List;

@WorkflowInterface
public interface SoftwareFactoryWorkflow {
    @WorkflowMethod(name = "SoftwareFactoryWorkflow")
    Result run(Request request);

    record Request(String taskId, String attemptId, String sourceCommit, String requirement,
                   List<DelegationWorkflow.Request> delegations) {
        public Request {
            delegations = delegations == null ? List.of() : List.copyOf(delegations);
        }

        public Request(String taskId, String attemptId, String sourceCommit, String requirement) {
            this(taskId, attemptId, sourceCommit, requirement, List.of());
        }
    }

    record Result(String taskId, String attemptId, String sourceCommit, String status, List<String> chronology,
                  List<DelegationWorkflow.Result> delegations) {
        public Result {
            chronology = List.copyOf(chronology);
            delegations = List.copyOf(delegations);
        }
    }
}
