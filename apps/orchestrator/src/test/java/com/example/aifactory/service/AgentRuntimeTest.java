package com.example.aifactory.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentRuntimeTest {
    @Test
    void executesOnlyExplicitToolsAndValidatesTheExplicitOutputContract() {
        PromptService prompts = mock(PromptService.class);
        LlmGatewayClient llm = mock(LlmGatewayClient.class);
        AgentContextToolHost host = mock(AgentContextToolHost.class);
        when(prompts.load("developer-v1")).thenReturn("system");
        when(prompts.fingerprint("developer-v1")).thenReturn("a".repeat(64));
        when(host.definitions()).thenReturn(List.of(
                new LlmGatewayClient.ToolDefinition("context.read_file", "read", Map.of("type", "object")),
                new LlmGatewayClient.ToolDefinition("context.search_code", "search", Map.of("type", "object"))));
        when(host.executor(org.mockito.ArgumentMatchers.eq("task-1"),
                org.mockito.ArgumentMatchers.eq("attempt-1"), org.mockito.ArgumentMatchers.eq("a".repeat(40)),
                org.mockito.ArgumentMatchers.eq("developer"), any(ExecutionIdentity.class)))
                .thenReturn(call -> "{}");
        when(host.authorization()).thenReturn((actor, tool) -> true);
        when(llm.nextToolTurn(any(), any(), anyInt())).thenReturn(new AgentToolLoop.Turn(AgentToolLoop.Stop.FINAL,
                event(), List.of(), 10, 5, 20));
        AgentRuntime runtime = new AgentRuntime(prompts, llm, host,
                new MultiAgentContractValidator(new ObjectMapper()));

        AgentRuntime.Result result = runtime.execute(invocation(Set.of("context.read_file")));

        assertThat(result.document().path("role").asText()).isEqualTo("developer");
        assertThat(result.promptFingerprint()).hasSize(64);
        assertThat(result.tokens()).isEqualTo(15);
    }

    @Test
    void rejectsToolsNotResolvedByTheHost() {
        PromptService prompts = mock(PromptService.class);
        AgentContextToolHost host = mock(AgentContextToolHost.class);
        when(host.definitions()).thenReturn(List.of());
        AgentRuntime runtime = new AgentRuntime(prompts, mock(LlmGatewayClient.class), host,
                new MultiAgentContractValidator(new ObjectMapper()));
        assertThatThrownBy(() -> runtime.execute(invocation(Set.of("context.unknown"))))
                .hasMessageContaining("unknown or unavailable tool");
    }

    @Test
    void rejectsEffectfulToolsEvenIfAHostAccidentallyAdvertisesThem() {
        AgentContextToolHost host = mock(AgentContextToolHost.class);
        when(host.definitions()).thenReturn(List.of(new LlmGatewayClient.ToolDefinition(
                "sandbox.apply_patch", "effect", Map.of("type", "object"))));
        AgentRuntime runtime = new AgentRuntime(mock(PromptService.class), mock(LlmGatewayClient.class), host,
                new MultiAgentContractValidator(new ObjectMapper()));

        assertThatThrownBy(() -> runtime.execute(invocation(Set.of("sandbox.apply_patch"))))
                .hasMessageContaining("cannot be injected");
    }

    @Test
    void rejectsAnInvocationThatExceedsTheRoleBudgetBeforeCallingDependencies() {
        AgentRuntime runtime = new AgentRuntime(mock(PromptService.class), mock(LlmGatewayClient.class),
                mock(AgentContextToolHost.class), new MultiAgentContractValidator(new ObjectMapper()));
        AgentRuntime.Invocation excessive = new AgentRuntime.Invocation(
                "task-1", "attempt-1", "a".repeat(40), "developer", "developer-v1",
                "agent-run-event-v1", Set.of(), Set.of(), "untrusted input",
                new AgentToolLoop.Budget(7, Duration.ofSeconds(30), 1_000, 1_000));

        assertThatThrownBy(() -> runtime.execute(excessive))
                .hasMessageContaining("agent budget exceeded").hasMessageContaining("developer");
    }

    @Test
    void killSwitchStopsTheRoleBeforeAnyModelOrToolCall(@TempDir Path temp) throws Exception {
        Path control = temp.resolve("kill-switch.properties");
        Files.writeString(control,
                "revision=1\nrole-modes.disabled=developer@HIERARCHICAL_CANARY\n");
        HierarchicalBudgetPolicy budgets = new HierarchicalBudgetPolicy();
        AgentRuntime runtime = new AgentRuntime(mock(PromptService.class), mock(LlmGatewayClient.class),
                mock(AgentContextToolHost.class), new MultiAgentContractValidator(new ObjectMapper()), budgets,
                new TaskUsageLedger(budgets), new OperationalKillSwitch(control));
        AgentRuntime.Invocation candidate = new AgentRuntime.Invocation(
                "task-1", "attempt-1", "a".repeat(40), "developer", "developer-v1",
                "agent-run-event-v1", Set.of(), Set.of(), "untrusted input",
                new AgentToolLoop.Budget(2, Duration.ofSeconds(30), 1000, 1000),
                "HIERARCHICAL_CANARY");

        assertThatThrownBy(() -> runtime.execute(candidate))
                .isInstanceOf(AgentToolLoop.AgentLoopException.class)
                .extracting(error -> ((AgentToolLoop.AgentLoopException) error).stopCondition())
                .isEqualTo(AgentToolLoop.StopCondition.POLICY_DENIED);
    }

    private static AgentRuntime.Invocation invocation(Set<String> tools) {
        return new AgentRuntime.Invocation("task-1", "attempt-1", "a".repeat(40), "developer",
                "developer-v1", "agent-run-event-v1", tools, Set.of("specialist-1", "node-1"),
                "untrusted input", new AgentToolLoop.Budget(2, Duration.ofSeconds(30), 1000, 1000));
    }

    private static String event() {
        return """
                {"schema_version":"1","event_id":"event-1","task_id":"task-1","attempt_id":"attempt-1",
                 "specialist_task_id":"specialist-1","node_id":"node-1","role":"developer","sequence":0,
                 "event_type":"STARTED","previous_state":"QUEUED","state":"RUNNING",
                 "usage_delta":{"turns":0,"input_tokens":0,"output_tokens":0,"cost_micros":null,"tool_calls":0,"duration_millis":0},
                 "reason":"started","occurred_at":"2026-09-02T10:00:00Z"}
                """;
    }
}
