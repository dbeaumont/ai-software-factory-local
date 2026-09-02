package com.example.aifactory.workflow.temporal;

import com.example.aifactory.service.AgentCatalog;
import com.example.aifactory.service.ContradictionClassifier;
import com.example.aifactory.service.DeterministicContradictionResolver;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Workflow-owned trigger for one bounded delegation intended to acquire contradiction-resolving evidence. */
public final class ContradictionEvidenceDelegator {
    private static final Pattern PATH = Pattern.compile("(?!/)(?!.*(?:^|/)\\.\\.(?:/|$))[^\\u0000]{1,512}");
    private static final Map<ContradictionClassifier.Classification, Map<EvidenceKind, Set<String>>> ROUTES = Map.of(
            ContradictionClassifier.Classification.FACTUAL, Map.of(
                    EvidenceKind.CONTEXT_FACT, Set.of("architecture-agent", "test-agent", "security-agent")),
            ContradictionClassifier.Classification.INCOMPATIBLE_SCOPE, Map.of(
                    EvidenceKind.SCOPE_ANALYSIS, Set.of("architecture-agent")),
            ContradictionClassifier.Classification.RISK, Map.of(
                    EvidenceKind.RISK_ANALYSIS, Set.of("security-agent")),
            ContradictionClassifier.Classification.MISSING_TEST, Map.of(
                    EvidenceKind.TEST_RESULT, Set.of("test-agent")),
            ContradictionClassifier.Classification.DIVERGENT_RECOMMENDATION, Map.of());

    private final DelegationScheduler scheduler;
    private final AgentCatalog catalog;

    public ContradictionEvidenceDelegator(DelegationScheduler scheduler, AgentCatalog catalog) {
        this.scheduler = Objects.requireNonNull(scheduler);
        this.catalog = Objects.requireNonNull(catalog);
    }

    public TriggeredDelegation trigger(SoftwareFactoryWorkflow.Request root,
                                       ContradictionClassifier.ClassifiedCandidate contradiction,
                                       DeterministicContradictionResolver.Result currentResolution,
                                       EvidenceOpportunity opportunity) {
        DelegationWorkflow.Request request = plan(root, contradiction, currentResolution, opportunity);
        DelegationWorkflow.Result result = scheduler.execute(root, request);
        return new TriggeredDelegation(request, result, contradiction.candidate().contradictionId(),
                opportunity.evidenceKind());
    }

    public DelegationWorkflow.Request plan(SoftwareFactoryWorkflow.Request root,
                                           ContradictionClassifier.ClassifiedCandidate contradiction,
                                           DeterministicContradictionResolver.Result currentResolution,
                                           EvidenceOpportunity opportunity) {
        requireInputs(root, contradiction, currentResolution, opportunity);
        Set<String> allowedRoles = ROUTES.getOrDefault(contradiction.classification(), Map.of())
                .getOrDefault(opportunity.evidenceKind(), Set.of());
        if (!allowedRoles.contains(opportunity.targetRole())) {
            throw new IllegalArgumentException("No targeted evidence route covers classification, kind and role");
        }
        AgentCatalog.Role role = catalog.require(opportunity.targetRole());
        if (!"supervisor".equals(role.parent()) || role.effectful()) {
            throw new SecurityException("Targeted evidence role must be a read-only Supervisor child");
        }
        String contradictionId = contradiction.candidate().contradictionId();
        String nodeId = "evidence-" + sha256(root.taskId() + '\n' + root.attemptId() + '\n' + contradictionId
                + '\n' + opportunity.evidenceKind() + '\n' + opportunity.targetRole()).substring(0, 24);
        String objective = "Resolve contradiction " + contradictionId + " on subject "
                + contradiction.candidate().subject() + " by producing " + opportunity.evidenceKind()
                + ". Expected evidence: " + opportunity.expectedEvidence() + ". Read paths: "
                + String.join(",", opportunity.readPaths());
        if (objective.length() > 2_000) throw new IllegalArgumentException("Targeted evidence objective is too long");
        return new DelegationWorkflow.Request(root.taskId(), root.attemptId(), nodeId, "supervisor",
                opportunity.targetRole(), root.sourceCommit(), objective, 10, Set.of(), budget(opportunity.targetRole()));
    }

    private static void requireInputs(SoftwareFactoryWorkflow.Request root,
                                      ContradictionClassifier.ClassifiedCandidate contradiction,
                                      DeterministicContradictionResolver.Result currentResolution,
                                      EvidenceOpportunity opportunity) {
        if (root == null || contradiction == null || contradiction.candidate() == null
                || !root.taskId().equals(contradiction.candidate().taskId())
                || !root.attemptId().equals(contradiction.candidate().attemptId())) {
            throw new IllegalArgumentException("Contradiction lineage differs from root workflow");
        }
        if (currentResolution == null || currentResolution.outcome()
                != DeterministicContradictionResolver.Outcome.OPEN) {
            throw new IllegalArgumentException("Only an open contradiction can request new evidence");
        }
        if (opportunity == null || opportunity.evidenceKind() == null
                || opportunity.targetRole() == null || opportunity.targetRole().isBlank()
                || opportunity.expectedEvidence() == null || opportunity.expectedEvidence().isBlank()
                || opportunity.expectedEvidence().length() > 1_000 || opportunity.readPaths() == null
                || opportunity.readPaths().size() > 32 || opportunity.readPaths().stream().distinct().count()
                != opportunity.readPaths().size() || opportunity.readPaths().stream()
                .anyMatch(path -> path == null || !PATH.matcher(path).matches())) {
            throw new IllegalArgumentException("Evidence opportunity is invalid");
        }
    }

    private static DelegationWorkflow.Budget budget(String role) {
        return switch (role) {
            case "architecture-agent", "security-agent" -> new DelegationWorkflow.Budget(6_000, 6_000_000, 4, 360);
            case "test-agent" -> new DelegationWorkflow.Budget(5_000, 5_000_000, 4, 360);
            default -> throw new IllegalArgumentException("No targeted evidence budget for role " + role);
        };
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record EvidenceOpportunity(EvidenceKind evidenceKind, String targetRole,
                                      String expectedEvidence, List<String> readPaths) {
        public EvidenceOpportunity {
            readPaths = readPaths == null ? null : List.copyOf(readPaths);
        }
    }

    public record TriggeredDelegation(DelegationWorkflow.Request request, DelegationWorkflow.Result result,
                                      String contradictionId, EvidenceKind expectedEvidenceKind) {}

    public enum EvidenceKind { CONTEXT_FACT, SCOPE_ANALYSIS, RISK_ANALYSIS, TEST_RESULT }
}
