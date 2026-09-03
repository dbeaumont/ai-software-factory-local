package com.example.aifactory.service;

import com.example.aifactory.workflow.temporal.DelegationScheduler;
import com.example.aifactory.workflow.temporal.DelegationWorkflow;
import com.example.aifactory.workflow.temporal.SoftwareFactoryWorkflow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Produces an immutable patch application order from the validated delegation DAG. */
@Component
public final class PatchIntegrationPlanner {
    private static final Set<String> PATCH_ROLES = Set.of("code-agent", "developer", "patch-repair");
    private final DelegationScheduler scheduler;
    private final PatchConflictDetector conflicts;

    @Autowired
    public PatchIntegrationPlanner(PatchConflictDetector conflicts) {
        this(new DelegationScheduler(), conflicts);
    }

    PatchIntegrationPlanner(DelegationScheduler scheduler, PatchConflictDetector conflicts) {
        this.scheduler = scheduler;
        this.conflicts = conflicts;
    }

    public IntegrationPlan plan(SoftwareFactoryWorkflow.Request root, List<DelegationWorkflow.Request> dag,
                                List<PatchCandidate> candidates) {
        List<DelegationWorkflow.Request> orderedDag = scheduler.validateAndOrder(root, dag);
        Map<String, Integer> dagOrder = new LinkedHashMap<>();
        Map<String, DelegationWorkflow.Request> nodes = new LinkedHashMap<>();
        for (int index = 0; index < orderedDag.size(); index++) {
            DelegationWorkflow.Request node = orderedDag.get(index);
            dagOrder.put(node.nodeId(), index);
            nodes.put(node.nodeId(), node);
        }
        Map<String, PatchCandidate> byNode = new LinkedHashMap<>();
        for (PatchCandidate candidate : candidates) {
            DelegationWorkflow.Request node = candidate == null ? null : nodes.get(candidate.nodeId());
            if (node == null || !PATCH_ROLES.contains(node.role())) {
                throw invalid("patch candidate is not bound to a Code DAG node");
            }
            if (candidate.proposalId() == null || candidate.patch() == null
                    || byNode.putIfAbsent(candidate.nodeId(), candidate) != null) {
                throw invalid("patch candidate identity is missing or duplicated");
            }
        }
        if (byNode.isEmpty()) throw invalid("integration contains no patch candidate");
        List<PatchCandidate> ordered = new ArrayList<>(byNode.values());
        ordered.sort(Comparator.comparingInt((PatchCandidate candidate) -> dagOrder.get(candidate.nodeId()))
                .thenComparing(PatchCandidate::nodeId).thenComparing(PatchCandidate::proposalId));
        if (ordered.size() > 1) {
            PatchConflictDetector.Report report = conflicts.detect(ordered.stream().map(candidate ->
                    new PatchConflictDetector.Candidate(candidate.proposalId(), candidate.patch())).toList());
            if (!report.safeToIntegrate()) {
                throw invalid("conflicting patches require repair or serialization: " + report.conflicts());
            }
        }
        List<PatchIdentity> identities = ordered.stream().map(candidate -> new PatchIdentity(
                candidate.nodeId(), candidate.proposalId(), candidate.patch().digest())).toList();
        return new IntegrationPlan(List.copyOf(ordered), digestIdentities(identities),
                "VALIDATED_DAG_TOPOLOGICAL_ORDER");
    }

    public static String digestIdentities(List<PatchIdentity> ordered) {
        if (ordered == null || ordered.isEmpty()) throw invalid("patch identities are required");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (PatchIdentity identity : ordered) {
                if (identity == null || identity.nodeId() == null || identity.proposalId() == null
                        || identity.patchDigest() == null || !identity.patchDigest().matches("[0-9a-f]{64}")) {
                    throw invalid("patch identity is invalid");
                }
                for (String field : List.of(identity.nodeId(), identity.proposalId(), identity.patchDigest())) {
                    byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
                    digest.update(java.nio.ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                    digest.update(bytes);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot digest patch integration plan", exception);
        }
    }

    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException("Invalid patch integration plan: " + reason);
    }

    public record PatchCandidate(String nodeId, String proposalId,
                                 PatchProposalValidator.ValidatedPatch patch) {}

    public record PatchIdentity(String nodeId, String proposalId, String patchDigest) {}

    public record IntegrationPlan(List<PatchCandidate> orderedPatches, String digest, String reason) {
        public IntegrationPlan {
            orderedPatches = List.copyOf(orderedPatches);
        }
    }
}
