package com.example.aifactory.workflow.temporal;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

import java.util.List;

@WorkflowInterface
public interface SoftwareFactoryWorkflow {
    @WorkflowMethod(name = "SoftwareFactoryWorkflow")
    Result run(Request request);

    record Request(String taskId, String attemptId, String sourceCommit, String requirement) {}

    record Result(String taskId, String attemptId, String sourceCommit, String status, List<String> chronology) {
        public Result {
            chronology = List.copyOf(chronology);
        }
    }
}
