package com.example.aifactory.service;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Applies host routing precedence and records every resulting decision. */
@Service
public final class WorkflowRoutingService {
    private final ShortCodePathPlanner shortPath;
    private final HierarchicalPathPlanner hierarchicalPath;
    private final RoutingDecisionJournal journal;

    public WorkflowRoutingService(ShortCodePathPlanner shortPath, HierarchicalPathPlanner hierarchicalPath,
                                  RoutingDecisionJournal journal) {
        this.shortPath = Objects.requireNonNull(shortPath);
        this.hierarchicalPath = Objects.requireNonNull(hierarchicalPath);
        this.journal = Objects.requireNonNull(journal);
    }

    public RoutingDecision decide(Input input) {
        requireIdentity(input);
        Map<String, String> facts = normalized(input);
        Selection selection = select(input);
        String decisionId = digest(input.taskId(), input.sourceCommit(), facts, selection.path());
        RoutingDecision decision = new RoutingDecision(decisionId, shortPath.policyId(),
                shortPath.policyVersion(), input.taskId(), input.sourceCommit(), input.mode(),
                selection.effectiveMode(), facts, selection.rule(), selection.path(), selection.reasons(),
                selection.agents(), selection.humanGate());
        journal.append(decision);
        return decision;
    }

    private Selection select(Input input) {
        if ("PIPELINE".equals(input.mode())) {
            return selection("PIPELINE", "mode-ceiling", "PIPELINE_BASELINE",
                    "Requested mode only authorizes the baseline pipeline.",
                    List.of("planner", "developer", "tester", "reviewer"), "R2".equals(input.risk())
                            ? "BEFORE_EXTERNAL_EFFECT" : "NONE");
        }
        if ("HIERARCHICAL_SHADOW".equals(input.mode())) {
            return selection("PIPELINE", "shadow-authority", "PIPELINE_BASELINE",
                    "Shadow mode records analysis while the baseline pipeline remains authoritative.",
                    List.of("planner", "developer", "tester", "reviewer"), "NONE");
        }
        if (!input.inputsComplete() || input.contradictory() || !input.budgetAvailable()) {
            return triage(input.mode(), "human-triage",
                    "Required routing facts are missing, contradictory, or lack an approved budget.");
        }
        if (!"QUALIFIED".equals(input.qualification())) {
            return triage(input.mode(), "qualification", "The task is not qualified for autonomous routing.");
        }
        if (Set.of("R3", "R4").contains(input.risk())) {
            return triage(input.mode(), "human-triage", "The risk class requires human triage before routing.");
        }
        if ("HIERARCHICAL_CANARY".equals(input.mode())
                && (!input.repositoryAllowlisted() || !input.stableCanaryBucket())) {
            return triage(input.mode(), "canary-eligibility",
                    "Repository allowlist and stable canary bucket are both required.");
        }
        var hierarchical = hierarchicalPath.plan(new HierarchicalPathPlanner.Input(
                input.mode(), input.qualification(), input.risk(), input.modules(), input.domains(),
                input.independentCodeScopes(), input.impacts(), input.materialDecisionOpen(),
                input.repositoryAllowlisted(), input.stableCanaryBucket(), input.inputsComplete(),
                input.contradictory(), input.budgetAvailable()));
        if (hierarchical.isPresent()) {
            HierarchicalPathPlanner.Plan plan = hierarchical.orElseThrow();
            List<String> agents = new ArrayList<>();
            agents.add(plan.supervisor());
            agents.addAll(plan.specialistAgents());
            return selection(input.mode(), "hierarchical-path", plan.path(),
                    "At least one configured cross-domain or uncertainty trigger matched.",
                    agents, plan.humanGate());
        }
        var shortPlan = shortPath.plan(new ShortCodePathPlanner.Input(
                input.mode(), input.qualification(), input.risk(), input.modules(), input.domains(),
                input.estimatedFiles(), input.impacts(), input.repositoryAllowlisted(),
                input.stableCanaryBucket(), input.inputsComplete(), input.contradictory(),
                input.budgetAvailable()));
        if (shortPlan.isPresent()) {
            ShortCodePathPlanner.Plan plan = shortPlan.orElseThrow();
            return selection(input.mode(), "short-code-path", plan.path(),
                    "Low-risk scope is limited to one module, one domain, and the configured file ceiling.",
                    plan.agents(), plan.humanGate());
        }
        return triage(input.mode(), "default-decision", "No executable route matched all policy constraints.");
    }

    private static Selection triage(String effectiveMode, String rule, String reason) {
        return selection(effectiveMode, rule, "HUMAN_TRIAGE", reason, List.of(), "BEFORE_CODE");
    }

    private static Selection selection(String effectiveMode, String rule, String path, String reason,
                                       List<String> agents, String humanGate) {
        return new Selection(effectiveMode, rule, path, List.of(reason), agents, humanGate);
    }

    private static Map<String, String> normalized(Input input) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("qualification", value(input.qualification()));
        values.put("repository_id", value(input.repositoryId()));
        values.put("risk", value(input.risk()));
        values.put("modules", Integer.toString(input.modules()));
        values.put("domains", Integer.toString(input.domains()));
        values.put("estimated_files", Integer.toString(input.estimatedFiles()));
        values.put("independent_code_scopes", Integer.toString(input.independentCodeScopes()));
        values.put("impacts", input.impacts().stream().sorted().reduce((left, right) -> left + "," + right)
                .orElse(""));
        values.put("material_decision_open", Boolean.toString(input.materialDecisionOpen()));
        values.put("repository_allowlisted", Boolean.toString(input.repositoryAllowlisted()));
        values.put("stable_canary_bucket", Boolean.toString(input.stableCanaryBucket()));
        values.put("inputs_complete", Boolean.toString(input.inputsComplete()));
        values.put("contradictory", Boolean.toString(input.contradictory()));
        values.put("budget_available", Boolean.toString(input.budgetAvailable()));
        return Map.copyOf(values);
    }

    private static String digest(String taskId, String sourceCommit, Map<String, String> facts, String path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(taskId.getBytes(StandardCharsets.UTF_8));
            digest.update(sourceCommit.getBytes(StandardCharsets.UTF_8));
            facts.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                digest.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '=');
                digest.update(entry.getValue().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            });
            digest.update(path.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    private static void requireIdentity(Input input) {
        if (input == null || input.taskId() == null || !input.taskId().matches("[A-Za-z0-9_-]{1,64}")
                || input.sourceCommit() == null || !input.sourceCommit().matches("[0-9a-f]{40}")
                || input.mode() == null || input.impacts() == null) {
            throw new IllegalArgumentException("Routing identity is invalid");
        }
    }

    private static String value(String value) {
        return value == null ? "<missing>" : value;
    }

    public record Input(String taskId, String sourceCommit, String mode, String qualification,
                        String repositoryId, String risk, int modules, int domains, int estimatedFiles,
                        int independentCodeScopes, Set<String> impacts, boolean materialDecisionOpen,
                        boolean repositoryAllowlisted, boolean stableCanaryBucket, boolean inputsComplete,
                        boolean contradictory, boolean budgetAvailable) {
        public Input {
            impacts = impacts == null ? Set.of() : Set.copyOf(impacts);
        }
    }

    private record Selection(String effectiveMode, String rule, String path, List<String> reasons,
                             List<String> agents, String humanGate) {}
}
