package com.example.aifactory.workflow.temporal;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Host-owned guard for Supervisor replan proposals. */
public final class DelegationReplanPolicy {
    static final int MAX_ACCEPTED_REPLANS = 2;
    private final DelegationScheduler scheduler;

    public DelegationReplanPolicy(DelegationScheduler scheduler) {
        this.scheduler = Objects.requireNonNull(scheduler);
    }

    public State initial(SoftwareFactoryWorkflow.Request root, List<DelegationWorkflow.Request> plan) {
        String digest = digest(root, plan);
        return new State(0, digest, List.of(digest));
    }

    public AcceptedReplan apply(SoftwareFactoryWorkflow.Request root, List<DelegationWorkflow.Request> currentPlan,
                                State state, Proposal proposal) {
        Objects.requireNonNull(proposal, "Replan proposal is required");
        if (!"supervisor".equals(proposal.actorRole())) throw invalid("only Supervisor may propose a replan");
        if (proposal.justification() == null || proposal.justification().isBlank()
                || proposal.justification().length() > 4_000) {
            throw invalid("a bounded justification is required");
        }
        String actualCurrentDigest = digest(root, currentPlan);
        State effectiveState = state == null ? initial(root, currentPlan) : state;
        if (effectiveState.acceptedReplans() >= MAX_ACCEPTED_REPLANS) {
            throw invalid("maximum accepted replans exceeded");
        }
        if (!actualCurrentDigest.equals(effectiveState.currentDagDigest())
                || !actualCurrentDigest.equals(proposal.expectedCurrentDagDigest())) {
            throw invalid("current DAG digest mismatch");
        }
        List<DelegationWorkflow.Request> replacement = scheduler.validateAndOrder(root, proposal.replacementPlan());
        String replacementDigest = digestOrdered(replacement);
        if (!replacementDigest.equals(proposal.replacementDagDigest())) {
            throw invalid("replacement DAG digest mismatch");
        }
        if (replacementDigest.equals(actualCurrentDigest)) throw invalid("replacement DAG digest is unchanged");
        List<String> history = new java.util.ArrayList<>(effectiveState.dagDigestHistory());
        history.add(replacementDigest);
        State updated = new State(effectiveState.acceptedReplans() + 1, replacementDigest, history);
        return new AcceptedReplan(replacement, updated, proposal.justification());
    }

    public String digest(SoftwareFactoryWorkflow.Request root, List<DelegationWorkflow.Request> plan) {
        return digestOrdered(scheduler.validateAndOrder(root, plan));
    }

    private static String digestOrdered(List<DelegationWorkflow.Request> ordered) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (DelegationWorkflow.Request node : ordered) {
                update(digest, node.taskId());
                update(digest, node.attemptId());
                update(digest, node.nodeId());
                update(digest, node.parentNodeId());
                update(digest, node.role());
                update(digest, node.sourceCommit());
                update(digest, node.objective());
                update(digest, node.priority());
                node.dependsOn().stream().sorted().forEach(dependency -> update(digest, dependency));
                update(digest, node.budget().maxTokens());
                update(digest, node.budget().maxCostMicros());
                update(digest, node.budget().maxTurns());
                update(digest, node.budget().timeoutSeconds());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    private static void update(MessageDigest digest, String value) {
        if (value == null) {
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(-1).array());
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static void update(MessageDigest digest, long value) {
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(value).array());
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("Invalid Supervisor replan: " + message);
    }

    public record Proposal(String actorRole, String expectedCurrentDagDigest, String replacementDagDigest,
                           String justification, List<DelegationWorkflow.Request> replacementPlan) {
        public Proposal {
            replacementPlan = replacementPlan == null ? List.of() : List.copyOf(replacementPlan);
        }
    }

    public record State(int acceptedReplans, String currentDagDigest, List<String> dagDigestHistory) {
        public State {
            if (acceptedReplans < 0 || acceptedReplans > MAX_ACCEPTED_REPLANS
                    || currentDagDigest == null || !currentDagDigest.matches("[0-9a-f]{64}")) {
                throw invalid("state is invalid");
            }
            dagDigestHistory = dagDigestHistory == null ? List.of() : List.copyOf(dagDigestHistory);
        }
    }

    public record AcceptedReplan(List<DelegationWorkflow.Request> plan, State state, String justification) {
        public AcceptedReplan {
            plan = List.copyOf(plan);
        }
    }
}
