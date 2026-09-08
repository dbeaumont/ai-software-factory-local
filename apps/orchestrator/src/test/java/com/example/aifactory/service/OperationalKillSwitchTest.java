package com.example.aifactory.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OperationalKillSwitchTest {
    @TempDir Path temp;

    @Test
    void reloadsGlobalServerToolAndRoleSwitchesWithoutRestart() throws Exception {
        Path file = temp.resolve("kill-switch.properties");
        OperationalKillSwitch switches = new OperationalKillSwitch(file);
        assertTrue(switches.decision("context", "context.read_file", "architecture-agent").allowed());

        Files.writeString(file, "revision=1\ntools.disabled=context.read_file\n");
        assertEquals("tool_kill_switch", switches.decision("context", "context.read_file", "architecture-agent").reason());

        Files.writeString(file, "revision=2\nroles.disabled=architecture-agent\n");
        assertEquals("role_kill_switch", switches.decision("context", "context.search_code", "architecture-agent").reason());

        Files.writeString(file, "revision=3\nservers.disabled=context\n");
        assertEquals("server_kill_switch", switches.decision("context", "context.search_code", "independent-reviewer").reason());

        Files.writeString(file, "revision=4\nglobal.disabled=true\n");
        assertEquals("global_kill_switch", switches.decision("other", "other.read", "independent-reviewer").reason());
    }

    @Test
    void failsClosedWhenOperationalFileIsMalformed() throws Exception {
        Path file = temp.resolve("kill-switch.properties");
        Files.writeString(file, "global.disabled=false\n");

        OperationalKillSwitch.Decision decision = new OperationalKillSwitch(file)
                .decision("context", "context.read_file", "architecture-agent");

        assertFalse(decision.allowed());
        assertEquals("invalid_control_file", decision.reason());
    }
}
