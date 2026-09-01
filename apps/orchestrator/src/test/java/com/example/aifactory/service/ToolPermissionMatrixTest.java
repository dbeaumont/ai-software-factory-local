package com.example.aifactory.service;

import org.junit.jupiter.api.Test;

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
}
