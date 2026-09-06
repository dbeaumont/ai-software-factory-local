package com.example.aifactory.agentruntime;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.WorkflowIdConflictPolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;

/** SDK boundary for idempotent workflow start and cancellation signal. */
public final class TemporalAgentTaskWorkflowGateway
        implements AgentTaskWorkflowStarter, AgentTaskWorkflowControl {
    private final WorkflowClient client;
    private final AgentTemporalProperties properties;
    private final String role;

    public TemporalAgentTaskWorkflowGateway(WorkflowClient client, AgentTemporalProperties properties, String role) {
        this.client = client;
        this.properties = properties;
        this.role = role;
    }

    @Override
    public Execution start(A2aSendMessageService.Submission submission, String envelopeJson) {
        if (!role.equals(submission.role())) throw new SecurityException("Workflow role differs from runtime role");
        String workflowId = workflowId(role, submission.taskId());
        AgentTaskWorkflowV1 workflow = client.newWorkflowStub(AgentTaskWorkflowV1.class,
                WorkflowOptions.newBuilder().setWorkflowId(workflowId)
                        .setWorkflowIdConflictPolicy(WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_USE_EXISTING)
                        .setTaskQueue(properties.taskQueue(role)).build());
        try {
            WorkflowExecution execution = WorkflowClient.start(workflow::run,
                    new AgentTaskWorkflowV1.Input(submission.taskId(), submission.contextId(), role,
                            submission.skill(), envelopeJson));
            return new Execution(execution.getWorkflowId(), execution.getRunId());
        } catch (WorkflowExecutionAlreadyStarted existing) {
            return new Execution(existing.getExecution().getWorkflowId(), existing.getExecution().getRunId());
        }
    }

    @Override
    public void requestCancellation(String taskId, String contextId, String reason) {
        client.newWorkflowStub(AgentTaskWorkflowV1.class, workflowId(role, taskId)).cancel(reason);
    }

    static String workflowId(String role, String taskId) {
        return "a2a-agent-task-v1/" + role + "/" + taskId;
    }
}
