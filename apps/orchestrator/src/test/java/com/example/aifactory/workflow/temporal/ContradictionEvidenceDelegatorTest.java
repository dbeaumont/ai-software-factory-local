package com.example.aifactory.workflow.temporal;

import com.example.aifactory.service.AgentCatalog;
import com.example.aifactory.service.ContradictionClassifier;
import com.example.aifactory.service.CrossPerimeterContradictionDetector;
import com.example.aifactory.service.DecisionAuthorityPolicy;
import com.example.aifactory.service.DeterministicContradictionResolver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContradictionEvidenceDelegatorTest {
    private DelegationWorkflow.Request launched;
    private final DelegationScheduler scheduler = new DelegationScheduler((workflowId, request) -> {
        launched = request;
        return () -> new DelegationWorkflow.Result(request.nodeId(), request.role(), "SUCCESS");
    });
    private final ContradictionEvidenceDelegator delegator =
            new ContradictionEvidenceDelegator(scheduler, new AgentCatalog());

    @Test
    void triggersOneBoundedTargetedDelegationWhenNewEvidenceCanResolveTheContradiction() {
        var root = root();
        var contradiction = contradiction(ContradictionClassifier.Classification.MISSING_TEST);
        var opportunity = new ContradictionEvidenceDelegator.EvidenceOpportunity(
                ContradictionEvidenceDelegator.EvidenceKind.TEST_RESULT, "test-agent",
                "Execution déterministe couvrant acceptance-42", List.of("apps/orchestrator"));

        ContradictionEvidenceDelegator.TriggeredDelegation triggered = delegator.trigger(
                root, contradiction, openResolution(), opportunity);

        assertThat(triggered.request()).isEqualTo(launched);
        assertThat(triggered.result().status()).isEqualTo("SUCCESS");
        assertThat(triggered.request().role()).isEqualTo("test-agent");
        assertThat(triggered.request().parentNodeId()).isEqualTo("supervisor");
        assertThat(triggered.request().sourceCommit()).isEqualTo(root.sourceCommit());
        assertThat(triggered.request().objective()).contains("contradiction-1", "acceptance-42");
        assertThat(triggered.request().budget()).isEqualTo(new DelegationWorkflow.Budget(5_000, 5_000_000, 4, 360));
    }

    @Test
    void createsAStableDelegationIdentityForTheSameEvidenceOpportunity() {
        var opportunity = new ContradictionEvidenceDelegator.EvidenceOpportunity(
                ContradictionEvidenceDelegator.EvidenceKind.RISK_ANALYSIS, "security-agent",
                "Threat model actualisé", List.of("apps/orchestrator"));
        var contradiction = contradiction(ContradictionClassifier.Classification.RISK);

        DelegationWorkflow.Request first = delegator.plan(root(), contradiction, openResolution(), opportunity);
        DelegationWorkflow.Request second = delegator.plan(root(), contradiction, openResolution(), opportunity);

        assertThat(first).isEqualTo(second);
        assertThat(first.nodeId()).startsWith("evidence-").hasSize(33);
    }

    @Test
    void rejectsUnsupportedRoutesClosedOrForeignContradictions() {
        var recommendation = contradiction(ContradictionClassifier.Classification.DIVERGENT_RECOMMENDATION);
        var testOpportunity = new ContradictionEvidenceDelegator.EvidenceOpportunity(
                ContradictionEvidenceDelegator.EvidenceKind.TEST_RESULT, "test-agent",
                "A test", List.of("apps"));

        assertThatThrownBy(() -> delegator.plan(root(), recommendation, openResolution(), testOpportunity))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("No targeted evidence route");
        assertThatThrownBy(() -> delegator.plan(root(), contradiction(
                        ContradictionClassifier.Classification.MISSING_TEST), resolved(), testOpportunity))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("open contradiction");

        var foreignRoot = new SoftwareFactoryWorkflow.Request("other-task", "attempt-1", "repo",
                "a".repeat(40), "requirement");
        assertThatThrownBy(() -> delegator.plan(foreignRoot, contradiction(
                        ContradictionClassifier.Classification.MISSING_TEST), openResolution(), testOpportunity))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("lineage");
    }

    private static SoftwareFactoryWorkflow.Request root() {
        return new SoftwareFactoryWorkflow.Request("task-1", "attempt-1", "repo",
                "a".repeat(40), "requirement");
    }

    private static ContradictionClassifier.ClassifiedCandidate contradiction(
            ContradictionClassifier.Classification classification) {
        var sourceA = new CrossPerimeterContradictionDetector.Source("result-a",
                CrossPerimeterContradictionDetector.Perimeter.ARCHITECTURE, "architecture-agent", "A",
                "b".repeat(64), List.of("evidence://task-1/a"));
        var sourceB = new CrossPerimeterContradictionDetector.Source("result-b",
                CrossPerimeterContradictionDetector.Perimeter.TESTS, "test-agent", "B",
                "c".repeat(64), List.of("evidence://task-1/b"));
        var candidate = new CrossPerimeterContradictionDetector.Candidate("contradiction-1", "task-1",
                "attempt-1", "shared.subject", dimension(classification), List.of(sourceA, sourceB));
        return new ContradictionClassifier.ClassifiedCandidate(candidate, classification, schemaType(classification));
    }

    private static CrossPerimeterContradictionDetector.Dimension dimension(
            ContradictionClassifier.Classification classification) {
        return switch (classification) {
            case FACTUAL -> CrossPerimeterContradictionDetector.Dimension.FACT;
            case INCOMPATIBLE_SCOPE -> CrossPerimeterContradictionDetector.Dimension.SCOPE;
            case RISK -> CrossPerimeterContradictionDetector.Dimension.RISK;
            case MISSING_TEST -> CrossPerimeterContradictionDetector.Dimension.TEST_COVERAGE;
            case DIVERGENT_RECOMMENDATION -> CrossPerimeterContradictionDetector.Dimension.RECOMMENDATION;
        };
    }

    private static String schemaType(ContradictionClassifier.Classification classification) {
        return switch (classification) {
            case FACTUAL -> "FACT";
            case INCOMPATIBLE_SCOPE -> "SCOPE";
            case RISK -> "RISK";
            case MISSING_TEST -> "TEST";
            case DIVERGENT_RECOMMENDATION -> "RECOMMENDATION";
        };
    }

    private static DeterministicContradictionResolver.Result openResolution() {
        return new DeterministicContradictionResolver.Result(DeterministicContradictionResolver.Outcome.OPEN,
                null, null, DecisionAuthorityPolicy.Authority.SUPERVISOR, List.of("supervisor"), "open");
    }

    private static DeterministicContradictionResolver.Result resolved() {
        return new DeterministicContradictionResolver.Result(DeterministicContradictionResolver.Outcome.RESOLVED,
                DecisionAuthorityPolicy.Verdict.ALLOW, "rule", DecisionAuthorityPolicy.Authority.POLICY,
                List.of("policy"), "resolved");
    }
}
