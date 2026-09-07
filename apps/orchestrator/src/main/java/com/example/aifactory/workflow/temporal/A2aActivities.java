package com.example.aifactory.workflow.temporal;

import com.example.aifactory.a2a.A2aContracts;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.workflow.Workflow;

import java.util.List;

/** Five independently configured Temporal activity boundaries for A2A client operations. */
public final class A2aActivities {
    private A2aActivities() {}

    @ActivityInterface
    public interface ResolveAgent {
        @ActivityMethod(name = "A2aResolveAgent")
        A2aContracts.AgentCardDescriptor resolveAgent(String agentRole);
    }

    @ActivityInterface
    public interface DispatchTask {
        @ActivityMethod(name = "A2aDispatchTask")
        A2aContracts.TaskSnapshot dispatchTask(DispatchRequest request);
    }

    @ActivityInterface
    public interface GetTask {
        @ActivityMethod(name = "A2aGetTask")
        A2aContracts.TaskSnapshot getTask(A2aContracts.TaskQuery query);
    }

    @ActivityInterface
    public interface CancelTask {
        @ActivityMethod(name = "A2aCancelTask")
        A2aContracts.TaskSnapshot cancelTask(A2aContracts.TaskQuery query);
    }

    @ActivityInterface
    public interface ValidateArtifacts {
        @ActivityMethod(name = "A2aValidateArtifacts")
        ValidatedArtifacts validateArtifacts(ValidationRequest request);
    }

    @ActivityInterface
    public interface ReconcileDispatch {
        @ActivityMethod(name = "A2aReconcileDispatch")
        A2aContracts.TaskSnapshot reconcileDispatch(DispatchRequest request);
    }

    @ActivityInterface
    public interface ContinueTask {
        @ActivityMethod(name = "A2aContinueTask")
        A2aContracts.TaskSnapshot continueTask(ContinuationRequest request);
    }

    public static Stubs newStubs() {
        return new Stubs(
                Workflow.newActivityStub(ResolveAgent.class,
                        TemporalActivityPolicies.forKind(TemporalActivityPolicies.Kind.A2A_RESOLVE)),
                Workflow.newActivityStub(DispatchTask.class,
                        TemporalActivityPolicies.forKind(TemporalActivityPolicies.Kind.A2A_DISPATCH)),
                Workflow.newActivityStub(GetTask.class,
                        TemporalActivityPolicies.forKind(TemporalActivityPolicies.Kind.A2A_GET)),
                Workflow.newActivityStub(CancelTask.class,
                        TemporalActivityPolicies.forKind(TemporalActivityPolicies.Kind.A2A_CANCEL)),
                Workflow.newActivityStub(ValidateArtifacts.class,
                        TemporalActivityPolicies.forKind(TemporalActivityPolicies.Kind.A2A_VALIDATE)),
                Workflow.newActivityStub(ReconcileDispatch.class,
                        TemporalActivityPolicies.forKind(TemporalActivityPolicies.Kind.A2A_RECONCILE)),
                Workflow.newActivityStub(ContinueTask.class,
                        TemporalActivityPolicies.forKind(TemporalActivityPolicies.Kind.A2A_CONTINUE)));
    }

    public record ValidationRequest(String agentRole, String outputContract, String taskId, String attemptId,
                                    A2aContracts.TaskSnapshot task) {}

    public record DispatchRequest(com.example.aifactory.a2a.A2aExecutionContext execution,
                                  String agentCardDigest, A2aContracts.SendCommand command) {}

    public record ContinuationRequest(com.example.aifactory.a2a.A2aExecutionContext execution,
                                      A2aContracts.SendCommand command) {}

    public record EvidenceReference(String artifactId, String uri, String digest, String contract) {}

    public record ValidatedArtifacts(String taskId, List<EvidenceReference> references) {
        public ValidatedArtifacts { references = List.copyOf(references); }
    }

    public record Stubs(ResolveAgent resolveAgent, DispatchTask dispatchTask, GetTask getTask,
                        CancelTask cancelTask, ValidateArtifacts validateArtifacts,
                        ReconcileDispatch reconcileDispatch, ContinueTask continueTask) {}
}
