package com.example.aifactory.service;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolPermissionMatrixTest {
    private final ToolPermissionMatrix matrix = ToolPermissionMatrix.readOnlyAgents();

    @Test
    void usesOnlyHostSuppliedRoleAndDeniesUnknowns() {
        assertTrue(matrix.isAllowed(new AgentToolLoop.Actor("task", "planner"), "context.read_file"));
        assertFalse(matrix.isAllowed(new AgentToolLoop.Actor("task", "planner"), "sandbox.apply_patch"));
        assertFalse(matrix.isAllowed(new AgentToolLoop.Actor("task", "model-claims-workflow"), "context.read_file"));
        assertFalse(matrix.isAllowed(new AgentToolLoop.Actor("task", "planner"), "unknown.dynamic_tool"));
    }

    @Test
    void plannerAndReviewerReceiveOnlyTheirAuthorizedReadSurface() {
        var planner = new AgentToolLoop.Actor("task", "planner");
        var reviewer = new AgentToolLoop.Actor("task", "reviewer");

        assertTrue(matrix.isAllowed(planner, "evidence.get_summary"));
        assertFalse(matrix.isAllowed(planner, "evidence.read"));
        assertTrue(matrix.isAllowed(reviewer, "evidence.get_summary"));
        assertTrue(matrix.isAllowed(reviewer, "evidence.read"));
        assertFalse(matrix.isAllowed(planner, "evidence.store"));
        assertFalse(matrix.isAllowed(reviewer, "evidence.create_manifest"));
        assertFalse(matrix.isAllowed(planner, "scm.create_draft_pull_request"));
        assertFalse(matrix.isAllowed(reviewer, "sandbox.run_tests"));
    }

    @Test
    void effectfulPipelineOperationsRemainWorkflowOnly() {
        for (String tool : ToolPermissionMatrix.WORKFLOW_EFFECTS) {
            assertTrue(matrix.isAllowed(new AgentToolLoop.Actor("engine", "workflow"), tool), tool);
            for (String role : new String[]{"planner", "developer", "patch-repair", "tester", "reviewer"}) {
                assertFalse(matrix.isAllowed(new AgentToolLoop.Actor("agent", role), tool), role + " / " + tool);
            }
        }
    }

    @Test
    void registersEveryHierarchicalAgentAndStillDeniesUnknownRoles() {
        AgentCatalog catalog = new AgentCatalog();
        Set<String> agents = catalog.roles().keySet().stream()
                .filter(role -> !"workflow".equals(role)).collect(java.util.stream.Collectors.toSet());

        for (String role : agents) {
            for (String tool : catalog.require(role).tools()) {
                assertTrue(matrix.isAllowed(new AgentToolLoop.Actor("task", role), tool), role + " / " + tool);
            }
        }
        assertFalse(matrix.isAllowed(new AgentToolLoop.Actor("task", "invented-agent"), "context.read_file"));
    }
}
