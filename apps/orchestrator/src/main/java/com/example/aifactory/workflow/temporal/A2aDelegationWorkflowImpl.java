package com.example.aifactory.workflow.temporal;

import com.example.aifactory.a2a.A2aContracts;
import com.example.aifactory.a2a.A2aExecutionContext;
import com.example.aifactory.a2a.A2aExtensions;
import com.example.aifactory.a2a.A2aMediaTypes;
import io.temporal.common.VersioningBehavior;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowVersioningBehavior;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Post-cutover child workflow: every specialist execution crosses the A2A activity boundary. */
public final class A2aDelegationWorkflowImpl implements DelegationWorkflow {
    private final A2aActivities.Stubs activities = A2aActivities.newStubs();
    private final A2aTaskAwaiter tasks = new A2aTaskAwaiter();

    @Override
    @WorkflowVersioningBehavior(VersioningBehavior.PINNED)
    public Result run(Request request) {
        requireRequest(request);
        A2aContracts.AgentCardDescriptor card = activities.resolveAgent().resolveAgent(request.role());
        String skill = inputSkill(request.role());
        if (!card.skillIds().contains(skill)) {
            throw io.temporal.failure.ApplicationFailure.newNonRetryableFailure(
                    "Agent Card does not expose required skill", "INCOMPATIBLE_SCHEMA");
        }
        var info = Workflow.getInfo();
        A2aExecutionContext execution = new A2aExecutionContext("1", request.taskId(), request.attemptId(),
                info.getWorkflowId(), info.getRunId(), request.taskId(), request.sourceCommit(), request.nodeId(),
                "supervisor".equals(request.parentNodeId()) ? null : request.parentNodeId(), request.role(),
                List.of(request.objectiveDigest()));
        String messageId = TemporalIds.sha256(String.join("\n", request.nodeId(), request.role(),
                request.objectiveDigest(), card.cardDigest()));
        Map<String, Object> instruction = Map.of(
                "schema_version", "1", "contract", inputContract(request.role()),
                "objective_digest", request.objectiveDigest(), "node_id", request.nodeId());
        Map<String, Object> metadata = Map.of(A2aExtensions.EXECUTION_CONTEXT_V1, executionMetadata(execution),
                "budget", Map.of("maxTokens", request.budget().maxTokens(),
                        "maxCostMicros", request.budget().maxCostMicros(), "maxTurns", request.budget().maxTurns(),
                        "timeoutSeconds", request.budget().timeoutSeconds()));
        A2aContracts.SendCommand command = new A2aContracts.SendCommand(request.role(), skill, messageId,
                null, null, List.of(new A2aContracts.Part(A2aMediaTypes.JSON, null, instruction, null)),
                metadata, true);
        A2aContracts.TaskSnapshot submitted = activities.dispatchTask().dispatchTask(
                new A2aActivities.DispatchRequest(execution, card.cardDigest(), command));
        A2aContracts.Notification terminal = tasks.awaitUntilTerminal(request.role(), submitted,
                Duration.ofSeconds(30), activities.getTask());
        if (terminal.state() == A2aContracts.TaskState.COMPLETED) {
            A2aContracts.TaskSnapshot completed = new A2aContracts.TaskSnapshot(terminal.taskId(),
                    terminal.contextId(), terminal.state(), terminal.occurredAt(), terminal.artifacts(),
                    Map.of("sequence", terminal.sequence()));
            activities.validateArtifacts().validateArtifacts(new A2aActivities.ValidationRequest(
                    request.role(), outputContract(request.role()), completed));
            return new Result(request.nodeId(), request.role(), "READY_FOR_ACTIVITIES");
        }
        return new Result(request.nodeId(), request.role(), switch (terminal.state()) {
            case CANCELED -> "CANCELLED";
            case REJECTED, FAILED -> "FAILED";
            default -> "INDETERMINATE";
        });
    }

    @Override
    public void a2aTaskUpdate(A2aContracts.Notification notification) {
        tasks.accept(notification);
    }

    private static void requireRequest(Request request) {
        if (request == null || request.taskId() == null || request.attemptId() == null
                || request.nodeId() == null || !request.nodeId().matches("[A-Za-z0-9_-]{1,128}")
                || request.role() == null || !request.role().matches("[a-z][a-z0-9-]{1,63}")
                || request.sourceCommit() == null || !request.sourceCommit().matches("[0-9a-f]{40}")
                || request.objectiveDigest() == null || !request.objectiveDigest().matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Delegation workflow request is invalid");
        }
    }

    private static String inputSkill(String role) {
        return role + "." + inputContract(role);
    }

    private static String inputContract(String role) {
        return switch (role) {
            case "developer" -> "code-task-v1";
            case "patch-repair" -> "patch-repair-task-v1";
            default -> "specialist-task-v1";
        };
    }

    private static String outputContract(String role) {
        return switch (role) {
            case "architecture-agent" -> "architecture-assessment-v1";
            case "code-agent" -> "integration-proposal-v1";
            case "developer" -> "patch-proposal-v1";
            case "patch-repair" -> "patch-repair-proposal-v1";
            case "test-design" -> "test-strategy-v1";
            case "test-agent", "test-evidence" -> "test-assessment-v1";
            case "security-agent", "security-findings", "threat-model" -> "security-assessment-v1";
            case "independent-reviewer" -> "independent-review-v1";
            case "supervisor" -> "supervisor-decision-v1";
            default -> "specialist-result-v1";
        };
    }

    private static Map<String, Object> executionMetadata(A2aExecutionContext value) {
        java.util.LinkedHashMap<String, Object> metadata = new java.util.LinkedHashMap<>();
        metadata.put("schemaVersion", value.schemaVersion());
        metadata.put("taskId", value.taskId());
        metadata.put("attemptId", value.attemptId());
        metadata.put("workflowId", value.workflowId());
        metadata.put("workflowRunId", value.workflowRunId());
        metadata.put("repositoryId", value.repositoryId());
        metadata.put("sourceCommit", value.sourceCommit());
        metadata.put("delegationId", value.delegationId());
        if (value.parentDelegationId() != null) metadata.put("parentDelegationId", value.parentDelegationId());
        metadata.put("agentRole", value.agentRole());
        metadata.put("inputDigests", value.inputDigests());
        return Map.copyOf(metadata);
    }
}
