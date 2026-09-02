package com.example.aifactory.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OperationalKillSwitchTest {
    @TempDir Path temp;

    @Test
    void reloadsGlobalServerToolAndRoleSwitchesWithoutRestart() throws Exception {
        Path file = temp.resolve("kill-switch.properties");
        OperationalKillSwitch switches = new OperationalKillSwitch(file);
        assertTrue(switches.decision("context", "context.read_file", "planner").allowed());

        Files.writeString(file, "revision=1\ntools.disabled=context.read_file\n");
        assertEquals("tool_kill_switch", switches.decision("context", "context.read_file", "planner").reason());

        Files.writeString(file, "revision=2\nroles.disabled=planner\n");
        assertEquals("role_kill_switch", switches.decision("context", "context.search_code", "planner").reason());

        Files.writeString(file, "revision=3\nservers.disabled=context\n");
        assertEquals("server_kill_switch", switches.decision("context", "context.search_code", "reviewer").reason());

        Files.writeString(file, "revision=4\nglobal.disabled=true\n");
        assertEquals("global_kill_switch", switches.decision("other", "other.read", "reviewer").reason());
    }

    @Test
    void disablesHierarchicalModesAndRoleModePairs() throws Exception {
        Path file = temp.resolve("kill-switch.properties");
        OperationalKillSwitch switches = new OperationalKillSwitch(file);

        Files.writeString(file, "revision=1\nmodes.disabled=HIERARCHICAL_CANARY\n");
        assertEquals("mode_kill_switch", switches.decision(
                "context", "context.read_file", "developer", "HIERARCHICAL_CANARY").reason());
        assertTrue(switches.decision(
                "context", "context.read_file", "developer", "HIERARCHICAL_ACTIVE").allowed());

        Files.writeString(file,
                "revision=2\nrole-modes.disabled=developer@HIERARCHICAL_ACTIVE\n");
        assertEquals("role_mode_kill_switch", switches.decision(
                "context", "context.read_file", "developer", "HIERARCHICAL_ACTIVE").reason());
        assertEquals("unknown_execution_mode", switches.decision(
                "context", "context.read_file", "developer", "MODEL_SELECTED").reason());
    }

    @Test
    void failsClosedWhenOperationalFileIsMalformed() throws Exception {
        Path file = temp.resolve("kill-switch.properties");
        Files.writeString(file, "global.disabled=false\n");

        OperationalKillSwitch.Decision decision = new OperationalKillSwitch(file)
                .decision("context", "context.read_file", "planner");

        assertFalse(decision.allowed());
        assertEquals("invalid_control_file", decision.reason());
    }

    @Test
    void stopsAnAgentToolLoopWhenActivatedDuringTheTask() {
        Path file = temp.resolve("kill-switch.properties");
        OperationalKillSwitch switches = new OperationalKillSwitch(file);
        AtomicInteger modelTurns = new AtomicInteger();
        AtomicInteger executions = new AtomicInteger();
        AgentToolLoop loop = new AgentToolLoop(messages -> {
            int turn = modelTurns.incrementAndGet();
            return new AgentToolLoop.Turn(AgentToolLoop.Stop.TOOL_CALLS, null,
                    List.of(new AgentToolLoop.ToolCall("call-" + turn, "context.read_file",
                            Map.of("path", "README.md"))), 1, 1, 0);
        }, call -> {
            executions.incrementAndGet();
            try {
                Files.writeString(file, "revision=1\ntools.disabled=context.read_file\n");
            } catch (java.io.IOException failure) {
                throw new java.io.UncheckedIOException(failure);
            }
            return "{}";
        }, ToolPermissionMatrix.readOnlyAgents(switches));

        AgentToolLoop.AgentLoopException failure = assertThrows(AgentToolLoop.AgentLoopException.class,
                () -> loop.run(new AgentToolLoop.Actor("task-live", "developer", "HIERARCHICAL_ACTIVE"),
                        "system", "request", new AgentToolLoop.Budget(
                                3, Duration.ofSeconds(10), 100, 100)));

        assertEquals("tool_denied", failure.reason());
        assertEquals(1, executions.get());
        assertEquals(2, modelTurns.get());
    }
}
