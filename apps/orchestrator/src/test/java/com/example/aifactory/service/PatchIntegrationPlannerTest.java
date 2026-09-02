package com.example.aifactory.service;

import com.example.aifactory.workflow.temporal.DelegationWorkflow;
import com.example.aifactory.workflow.temporal.SoftwareFactoryWorkflow;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PatchIntegrationPlannerTest {
    private final PatchIntegrationPlanner planner = new PatchIntegrationPlanner(new PatchConflictDetector());

    @Test
    void derivesStableIntegrationOrderAndDigestFromTheValidatedDagNotCandidateOrder() {
        DelegationWorkflow.Request codeB = node("code-b", 20, Set.of());
        DelegationWorkflow.Request codeA = node("code-a", 20, Set.of());
        DelegationWorkflow.Request codeC = node("code-c", 10, Set.of("code-a"));
        List<DelegationWorkflow.Request> dag = List.of(codeC, codeB, codeA);
        List<PatchIntegrationPlanner.PatchCandidate> reversed = List.of(
                candidate("code-c", "proposal-c", "src/C.java"),
                candidate("code-b", "proposal-b", "src/B.java"),
                candidate("code-a", "proposal-a", "src/A.java"));

        PatchIntegrationPlanner.IntegrationPlan first = planner.plan(root(), dag, reversed);
        PatchIntegrationPlanner.IntegrationPlan replay = planner.plan(root(),
                List.of(codeA, codeC, codeB), List.of(reversed.get(1), reversed.get(0), reversed.get(2)));

        assertThat(first.orderedPatches()).extracting(PatchIntegrationPlanner.PatchCandidate::nodeId)
                .containsExactly("code-a", "code-b", "code-c");
        assertThat(replay).isEqualTo(first);
        assertThat(first.digest()).hasSize(64);
        assertThat(first.reason()).isEqualTo("VALIDATED_DAG_TOPOLOGICAL_ORDER");
    }

    @Test
    void rejectsUnknownDuplicateAndConflictingPatchCandidates() {
        DelegationWorkflow.Request codeA = node("code-a", 20, Set.of());
        DelegationWorkflow.Request codeB = node("code-b", 20, Set.of());
        var first = candidate("code-a", "proposal-a", "src/A.java");

        assertThatThrownBy(() -> planner.plan(root(), List.of(codeA), List.of(
                candidate("unknown", "proposal-x", "src/X.java"))))
                .hasMessageContaining("not bound to a Code DAG node");
        assertThatThrownBy(() -> planner.plan(root(), List.of(codeA), List.of(first, first)))
                .hasMessageContaining("duplicated");
        assertThatThrownBy(() -> planner.plan(root(), List.of(codeA, codeB), List.of(
                first, candidate("code-b", "proposal-b", "src/A.java"))))
                .hasMessageContaining("conflicting patches");
    }

    private static SoftwareFactoryWorkflow.Request root() {
        return new SoftwareFactoryWorkflow.Request("task-1", "attempt-1", "a".repeat(40), "change");
    }

    private static DelegationWorkflow.Request node(String id, int priority, Set<String> dependencies) {
        return new DelegationWorkflow.Request("task-1", "attempt-1", id, "supervisor", "developer",
                "a".repeat(40), id, priority, dependencies, new DelegationWorkflow.Budget(100, 100, 1));
    }

    private static PatchIntegrationPlanner.PatchCandidate candidate(String nodeId, String proposalId, String path) {
        String content = "diff --git a/" + path + " b/" + path + "\n@@ -1 +1 @@\n-old\n+new\n";
        PatchProposalValidator.ValidatedPatch patch = new PatchProposalValidator.ValidatedPatch(
                content, PatchIntegrator.digestFor(content), content.getBytes(StandardCharsets.UTF_8).length,
                List.of(new PatchProposalValidator.FileChange(path, null, "MODIFY")));
        return new PatchIntegrationPlanner.PatchCandidate(nodeId, proposalId, patch);
    }
}
