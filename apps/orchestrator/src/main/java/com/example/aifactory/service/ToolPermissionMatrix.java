package com.example.aifactory.service;

import java.util.Map;
import java.util.Set;

/** Deny-by-default host policy. Roles are supplied by the workflow identity, never by model output. */
public final class ToolPermissionMatrix implements AgentToolLoop.ToolAuthorization {
    public static final Set<String> WORKFLOW_EFFECTS = Set.of(
            "sandbox.validate_patch", "sandbox.apply_patch", "sandbox.run_tests",
            "sandbox.run_quality", "sandbox.run_security", "sandbox.cancel_execution",
            "scm.create_draft_pull_request");
    private final Map<String, Set<String>> permissions;
    private final OperationalKillSwitch killSwitch;

    public ToolPermissionMatrix(Map<String, Set<String>> permissions) {
        this(permissions, null);
    }

    public ToolPermissionMatrix(Map<String, Set<String>> permissions, OperationalKillSwitch killSwitch) {
        this.permissions = permissions.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> Set.copyOf(entry.getValue())));
        this.killSwitch = killSwitch;
    }

    @Override
    public boolean isAllowed(AgentToolLoop.Actor actor, String toolName) {
        if (actor == null || toolName == null || toolName.isBlank()) {
            return false;
        }
        if (!permissions.getOrDefault(actor.role(), Set.of()).contains(toolName)) return false;
        String server = toolName.contains(".") ? toolName.substring(0, toolName.indexOf('.')) : "unknown";
        return killSwitch == null || killSwitch.decision(server, toolName, actor.role()).allowed();
    }

    public static ToolPermissionMatrix readOnlyAgents() {
        Set<String> context = Set.of(
                "context.list_tree", "context.search_code", "context.read_file",
                "context.get_repository_rules", "context.get_dependencies", "context.get_symbols");
        return new ToolPermissionMatrix(Map.of(
                "workflow", union(context, WORKFLOW_EFFECTS),
                "planner", union(context, Set.of("evidence.get_summary")),
                "reviewer", union(context, Set.of("evidence.get_summary", "evidence.read")),
                "developer", context,
                "patch-repair", Set.of("context.read_file", "context.get_symbols"),
                "tester", Set.of("context.search_code", "context.read_file",
                        "context.get_dependencies", "context.get_symbols")));
    }

    private static Set<String> union(Set<String> left, Set<String> right) {
        java.util.HashSet<String> result = new java.util.HashSet<>(left);
        result.addAll(right);
        return Set.copyOf(result);
    }
}
