package com.example.aifactory.service;

import com.example.aifactory.config.KillSwitchProperties;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

/** Read-through operational control. There is deliberately no application write API. */
@Component
public final class OperationalKillSwitch {
    public static final Set<String> EXECUTION_MODES = Set.of(
            "PIPELINE", "HIERARCHICAL_SHADOW", "HIERARCHICAL_CANARY", "HIERARCHICAL_ACTIVE");
    private final Path controlFile;

    @Autowired
    public OperationalKillSwitch(KillSwitchProperties properties) {
        this(properties.controlFile() == null || properties.controlFile().isBlank()
                ? null : Path.of(properties.controlFile()));
    }

    OperationalKillSwitch(Path controlFile) {
        this.controlFile = controlFile;
    }

    public Decision decision(String server, String tool, String role) {
        return decision(server, tool, role, "PIPELINE");
    }

    public Decision decision(String server, String tool, String role, String mode) {
        State state = load();
        if (!state.valid()) return new Decision(false, "invalid_control_file", state.revision());
        if (!EXECUTION_MODES.contains(mode)) return new Decision(false, "unknown_execution_mode", state.revision());
        if (state.globalDisabled()) return new Decision(false, "global_kill_switch", state.revision());
        if (state.servers().contains(server)) return new Decision(false, "server_kill_switch", state.revision());
        if (state.tools().contains(tool)) return new Decision(false, "tool_kill_switch", state.revision());
        if (state.roles().contains(role)) return new Decision(false, "role_kill_switch", state.revision());
        if (state.modes().contains(mode)) return new Decision(false, "mode_kill_switch", state.revision());
        if (state.roleModes().contains(role + '@' + mode))
            return new Decision(false, "role_mode_kill_switch", state.revision());
        return new Decision(true, "enabled", state.revision());
    }

    private State load() {
        if (controlFile == null || !Files.exists(controlFile)) return State.enabledByDefault();
        try {
            Properties values = new Properties();
            values.load(new StringReader(Files.readString(controlFile)));
            String revision = values.getProperty("revision", "").strip();
            if (revision.isEmpty()) return State.invalid("missing-revision");
            return new State(true, revision,
                    Boolean.parseBoolean(values.getProperty("global.disabled", "false")),
                    csv(values.getProperty("servers.disabled", "")),
                    csv(values.getProperty("tools.disabled", "")),
                    csv(values.getProperty("roles.disabled", "")),
                    csv(values.getProperty("modes.disabled", "")),
                    csv(values.getProperty("role-modes.disabled", "")));
        } catch (Exception exception) {
            return State.invalid("unreadable");
        }
    }

    private static Set<String> csv(String value) {
        return Arrays.stream(value.split(",")).map(String::strip).filter(item -> !item.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    public record Decision(boolean allowed, String reason, String revision) {
    }

    private record State(boolean valid, String revision, boolean globalDisabled,
                         Set<String> servers, Set<String> tools, Set<String> roles,
                         Set<String> modes, Set<String> roleModes) {
        static State enabledByDefault() {
            return new State(true, "default", false, Set.of(), Set.of(), Set.of(), Set.of(), Set.of());
        }
        static State invalid(String revision) {
            return new State(false, revision, true, Set.of(), Set.of(), Set.of(), Set.of(), Set.of());
        }
    }
}
