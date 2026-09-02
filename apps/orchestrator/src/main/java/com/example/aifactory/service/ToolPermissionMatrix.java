package com.example.aifactory.service;

import java.util.Map;
import java.util.Set;

/** Deny-by-default host policy. Roles are supplied by the workflow identity, never by model output. */
public final class ToolPermissionMatrix implements AgentToolLoop.ToolAuthorization {
    public static final Set<String> WORKFLOW_EFFECTS = Set.of(
            "sandbox.validate_patch", "sandbox.apply_patch", "sandbox.run_tests",
            "sandbox.run_quality", "sandbox.run_security", "sandbox.cancel_execution",
            "assurance.evaluate_quality_gate", "assurance.normalize_findings", "assurance.evaluate_policy",
            "evidence.store", "evidence.create_manifest", "scm.create_draft_pull_request");
    private final Map<String, Set<String>> permissions;
    private final OperationalKillSwitch killSwitch;
    private final SecurityAuditJournal audit;

    public ToolPermissionMatrix(Map<String, Set<String>> permissions) {
        this(permissions, null, null);
    }

    public ToolPermissionMatrix(Map<String, Set<String>> permissions, OperationalKillSwitch killSwitch) {
        this(permissions, killSwitch, null);
    }

    public ToolPermissionMatrix(Map<String, Set<String>> permissions, OperationalKillSwitch killSwitch,
                                SecurityAuditJournal audit) {
        this.permissions = permissions.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> Set.copyOf(entry.getValue())));
        this.killSwitch = killSwitch;
        this.audit = audit;
    }

    @Override
    public boolean isAllowed(AgentToolLoop.Actor actor, String toolName) {
        if (actor == null || toolName == null || toolName.isBlank()) {
            return false;
        }
        boolean allowed = permissions.getOrDefault(actor.role(), Set.of()).contains(toolName);
        String server = toolName.contains(".") ? toolName.substring(0, toolName.indexOf('.')) : "unknown";
        allowed = allowed && (killSwitch == null
                || killSwitch.decision(server, toolName, actor.role(), actor.executionMode()).allowed());
        if (audit != null) audit.append(allowed ? SecurityAuditJournal.EventType.AUTHORIZATION
                        : SecurityAuditJournal.EventType.REFUSAL,
                actor.subject(), actor.role(), toolName, allowed ? "ALLOW" : "DENY");
        return allowed;
    }

    public static ToolPermissionMatrix readOnlyAgents() {
        return readOnlyAgents(null);
    }

    public static ToolPermissionMatrix readOnlyAgents(OperationalKillSwitch killSwitch) {
        return readOnlyAgents(killSwitch, null);
    }

    public static ToolPermissionMatrix readOnlyAgents(OperationalKillSwitch killSwitch, SecurityAuditJournal audit) {
        Set<String> context = Set.of(
                "context.list_tree", "context.search_code", "context.read_file",
                "context.get_repository_rules", "context.get_dependencies", "context.get_symbols");
        return new ToolPermissionMatrix(Map.ofEntries(
                Map.entry("workflow", union(context, WORKFLOW_EFFECTS)),
                Map.entry("supervisor", Set.of("context.list_tree", "context.search_code",
                        "context.get_repository_rules", "context.get_dependencies", "evidence.get_summary")),
                Map.entry("architecture-agent", context),
                Map.entry("impact-analysis", Set.of("context.search_code", "context.read_file",
                        "context.get_repository_rules", "context.get_symbols")),
                Map.entry("dependencies-contracts", Set.of("context.list_tree", "context.read_file",
                        "context.get_dependencies")),
                Map.entry("code-agent", Set.of("context.list_tree", "context.search_code",
                        "context.get_repository_rules")),
                Map.entry("developer", context),
                Map.entry("patch-repair", Set.of("context.read_file", "context.get_symbols")),
                Map.entry("test-agent", Set.of("context.search_code", "context.read_file",
                        "context.get_dependencies", "context.get_symbols", "evidence.get_summary")),
                Map.entry("test-design", Set.of("context.search_code", "context.read_file",
                        "context.get_dependencies", "context.get_symbols")),
                Map.entry("test-evidence", Set.of("evidence.get_summary")),
                Map.entry("security-agent", Set.of("context.search_code", "context.read_file",
                        "context.get_dependencies", "context.get_symbols", "evidence.get_summary")),
                Map.entry("threat-model", Set.of("context.search_code", "context.read_file",
                        "context.get_dependencies", "context.get_symbols")),
                Map.entry("security-findings", Set.of("evidence.get_summary")),
                Map.entry("independent-reviewer", Set.of("context.search_code", "context.read_file",
                        "context.get_dependencies", "context.get_symbols", "evidence.get_summary", "evidence.read")),
                Map.entry("planner", union(context, Set.of("evidence.get_summary"))),
                Map.entry("reviewer", union(context, Set.of("evidence.get_summary", "evidence.read"))),
                Map.entry("tester", Set.of("context.search_code", "context.read_file",
                        "context.get_dependencies", "context.get_symbols"))), killSwitch, audit);
    }

    private static Set<String> union(Set<String> left, Set<String> right) {
        java.util.HashSet<String> result = new java.util.HashSet<>(left);
        result.addAll(right);
        return Set.copyOf(result);
    }
}
