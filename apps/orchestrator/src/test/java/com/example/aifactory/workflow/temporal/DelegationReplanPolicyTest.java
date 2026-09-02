package com.example.aifactory.workflow.temporal;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DelegationReplanPolicyTest {
    private final DelegationReplanPolicy policy = new DelegationReplanPolicy(
            new DelegationScheduler((workflowId, request) ->
                    () -> new DelegationWorkflow.Result(request.nodeId(), request.role(), "DONE")));

    @Test
    void acceptsAtMostTwoSupervisorReplansWithJustificationAndVerifiedNewDigest() {
        SoftwareFactoryWorkflow.Request root = root();
        List<DelegationWorkflow.Request> initial = List.of(node("architecture"));
        List<DelegationWorkflow.Request> second = List.of(node("code"));
        List<DelegationWorkflow.Request> third = List.of(node("tests"));
        DelegationReplanPolicy.State state = policy.initial(root, initial);

        DelegationReplanPolicy.AcceptedReplan first = policy.apply(root, initial, state,
                proposal(state.currentDagDigest(), policy.digest(root, second), second,
                        "Architecture evidence narrows the implementation to the code scope."));
        DelegationReplanPolicy.AcceptedReplan secondAccepted = policy.apply(root, second, first.state(),
                proposal(first.state().currentDagDigest(), policy.digest(root, third), third,
                        "The code result requires an isolated deterministic test pass."));

        assertThat(secondAccepted.state().acceptedReplans()).isEqualTo(2);
        assertThat(secondAccepted.state().dagDigestHistory()).hasSize(3);
        assertThat(secondAccepted.justification()).contains("deterministic test");
        assertThatThrownBy(() -> policy.apply(root, third, secondAccepted.state(),
                proposal(secondAccepted.state().currentDagDigest(), policy.digest(root, initial), initial,
                        "A third replacement must be rejected by the host ceiling.")))
                .hasMessageContaining("maximum accepted replans");
    }

    @Test
    void rejectsForeignActorsMissingJustificationStaleDigestsAndUnchangedPlans() {
        SoftwareFactoryWorkflow.Request root = root();
        List<DelegationWorkflow.Request> initial = List.of(node("architecture"));
        List<DelegationWorkflow.Request> replacement = List.of(node("code"));
        DelegationReplanPolicy.State state = policy.initial(root, initial);
        String replacementDigest = policy.digest(root, replacement);

        assertThatThrownBy(() -> policy.apply(root, initial, state, new DelegationReplanPolicy.Proposal(
                "developer", state.currentDagDigest(), replacementDigest, "valid reason", replacement)))
                .hasMessageContaining("only Supervisor");
        assertThatThrownBy(() -> policy.apply(root, initial, state, proposal(
                state.currentDagDigest(), replacementDigest, replacement, " ")))
                .hasMessageContaining("justification");
        assertThatThrownBy(() -> policy.apply(root, initial, state, proposal(
                "0".repeat(64), replacementDigest, replacement, "Replacement is required.")))
                .hasMessageContaining("current DAG digest mismatch");
        assertThatThrownBy(() -> policy.apply(root, initial, state, proposal(
                state.currentDagDigest(), "0".repeat(64), replacement, "Replacement is required.")))
                .hasMessageContaining("replacement DAG digest mismatch");
        assertThatThrownBy(() -> policy.apply(root, initial, state, proposal(
                state.currentDagDigest(), state.currentDagDigest(), initial, "Replacement is required.")))
                .hasMessageContaining("unchanged");
    }

    private static DelegationReplanPolicy.Proposal proposal(String currentDigest, String replacementDigest,
                                                             List<DelegationWorkflow.Request> replacement,
                                                             String justification) {
        return new DelegationReplanPolicy.Proposal(
                "supervisor", currentDigest, replacementDigest, justification, replacement);
    }

    private static SoftwareFactoryWorkflow.Request root() {
        return new SoftwareFactoryWorkflow.Request("task-1", "attempt-1", "a".repeat(40), "change");
    }

    private static DelegationWorkflow.Request node(String id) {
        return new DelegationWorkflow.Request("task-1", "attempt-1", id, "supervisor", "code-agent",
                "a".repeat(40), id, 100, Set.of(), new DelegationWorkflow.Budget(100, 100, 1));
    }
}
