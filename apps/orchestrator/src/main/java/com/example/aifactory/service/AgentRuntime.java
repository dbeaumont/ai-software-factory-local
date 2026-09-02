package com.example.aifactory.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Set;

/** Executes one explicitly configured role and validates its final structured result. */
@Component
public final class AgentRuntime implements AgentExecutor {
    private final PromptService prompts;
    private final LlmGatewayClient llm;
    private final AgentContextToolHost toolHost;
    private final MultiAgentContractValidator contracts;
    private final HierarchicalBudgetPolicy budgets;
    private final TaskUsageLedger usage;
    private final OperationalKillSwitch killSwitch;
    private final ExecutionTracer tracer;
    private final AgentMetrics metrics;
    private final AgentActivationGuard activation;

    public AgentRuntime(PromptService prompts, LlmGatewayClient llm, AgentContextToolHost toolHost,
                        MultiAgentContractValidator contracts) {
        this(prompts, llm, toolHost, contracts, new HierarchicalBudgetPolicy());
    }

    public AgentRuntime(PromptService prompts, LlmGatewayClient llm, AgentContextToolHost toolHost,
                        MultiAgentContractValidator contracts, HierarchicalBudgetPolicy budgets) {
        this(prompts, llm, toolHost, contracts, budgets, new TaskUsageLedger(budgets));
    }

    public AgentRuntime(PromptService prompts, LlmGatewayClient llm, AgentContextToolHost toolHost,
                        MultiAgentContractValidator contracts, HierarchicalBudgetPolicy budgets,
                        TaskUsageLedger usage) {
        this(prompts, llm, toolHost, contracts, budgets, usage, null);
    }

    public AgentRuntime(PromptService prompts, LlmGatewayClient llm, AgentContextToolHost toolHost,
                        MultiAgentContractValidator contracts, HierarchicalBudgetPolicy budgets,
                        TaskUsageLedger usage, OperationalKillSwitch killSwitch) {
        this(prompts, llm, toolHost, contracts, budgets, usage, killSwitch, ExecutionTracer.noop(),
                AgentMetrics.noop(), AgentActivationGuard.allowAllForTests());
    }

    public AgentRuntime(PromptService prompts, LlmGatewayClient llm, AgentContextToolHost toolHost,
                        MultiAgentContractValidator contracts, HierarchicalBudgetPolicy budgets,
                        TaskUsageLedger usage, OperationalKillSwitch killSwitch, ExecutionTracer tracer) {
        this(prompts, llm, toolHost, contracts, budgets, usage, killSwitch, tracer, AgentMetrics.noop(),
                AgentActivationGuard.allowAllForTests());
    }

    @Autowired
    public AgentRuntime(PromptService prompts, LlmGatewayClient llm, AgentContextToolHost toolHost,
                        MultiAgentContractValidator contracts, HierarchicalBudgetPolicy budgets,
                        TaskUsageLedger usage, OperationalKillSwitch killSwitch, ExecutionTracer tracer,
                        AgentMetrics metrics, AgentActivationGuard activation) {
        this.prompts = prompts;
        this.llm = llm;
        this.toolHost = toolHost;
        this.contracts = contracts;
        this.budgets = budgets;
        this.usage = usage;
        this.killSwitch = killSwitch;
        this.tracer = tracer;
        this.metrics = metrics;
        this.activation = activation;
    }

    @Override
    public Result execute(Invocation invocation) {
        activation.requireAllowed(invocation.role(), invocation.executionMode());
        if (killSwitch != null) {
            OperationalKillSwitch.Decision decision = killSwitch.decision(
                    "agent-runtime", "agent.execute", invocation.role(), invocation.executionMode());
            if (!decision.allowed()) {
                throw new AgentToolLoop.AgentLoopException("kill_switch",
                        "Agent invocation disabled by operations: " + decision.reason(),
                        AgentToolLoop.StopCondition.POLICY_DENIED);
            }
        }
        budgets.validateInvocation(invocation.role(), invocation.budget());
        if (invocation.allowedTools().stream().anyMatch(AgentRuntime::effectfulTool)) {
            throw new IllegalArgumentException("Effectful sandbox, assurance and SCM tools cannot be injected into AgentRuntime");
        }
        List<LlmGatewayClient.ToolDefinition> tools = toolHost.definitions().stream()
                .filter(definition -> invocation.allowedTools().contains(definition.name()))
                .toList();
        Set<String> resolved = tools.stream().map(LlmGatewayClient.ToolDefinition::name)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!resolved.equals(invocation.allowedTools())) {
            throw new IllegalArgumentException("Invocation contains an unknown or unavailable tool");
        }

        String prompt = prompts.load(invocation.promptName());
        String fingerprint = prompts.fingerprint(invocation.promptName());
        AgentToolLoop loop = new AgentToolLoop(
                messages -> tracer.trace(ExecutionTracer.SpanKind.LLM, invocation.executionIdentity(),
                        invocation.role() + ".completion", () -> llm.nextToolTurn(
                        messages, tools, Math.min(invocation.budget().maxTokens(), 8_192))),
                toolHost.executor(invocation.taskId(), invocation.attemptId(),
                        invocation.sourceCommit(), invocation.role(), invocation.executionIdentity()),
                toolHost.authorization(), AgentToolLoop.SafetyLimits.defaults(), delta -> {
                    metrics.recordUsage(invocation.role(), delta);
                    usage.consume(invocation.taskId(), invocation.attemptId(), usageLane(invocation.role()),
                            new TaskUsageLedger.Delta(delta.inputTokens(), delta.outputTokens(), delta.costMicros(),
                                    delta.turns(), 0));
                });
        long started = System.nanoTime();
        boolean validatingContract = false;
        try {
            AgentToolLoop.Result result = tracer.trace(ExecutionTracer.SpanKind.ACTIVITY,
                    invocation.executionIdentity(), invocation.role() + ".agent", () -> loop.run(new AgentToolLoop.Actor(
                                    invocation.taskId(), invocation.role(), invocation.executionMode()),
                            prompt, invocation.untrustedInput(), invocation.budget()));
            validatingContract = true;
            JsonNode document = contracts.validate(invocation.outputContract(), result.finalResult(),
                    new MultiAgentContractValidator.ContractContext(invocation.taskId(), invocation.attemptId(),
                            invocation.allowedReferenceIds()));
            validateHostBindings(invocation, document);
            validatingContract = false;
            metrics.recordDuration(invocation.role(), "success", System.nanoTime() - started);
            return new Result(document, fingerprint, result.turns(), result.tokens(), result.costMicros());
        } catch (AgentToolLoop.AgentLoopException failure) {
            metrics.recordFailure(invocation.role(), failure);
            metrics.recordDuration(invocation.role(), "error", System.nanoTime() - started);
            throw failure;
        } catch (RuntimeException failure) {
            if (validatingContract) metrics.recordContractFailure(invocation.role());
            metrics.recordDuration(invocation.role(), "error", System.nanoTime() - started);
            throw failure;
        }
    }

    static boolean effectfulTool(String name) {
        return name.startsWith("sandbox.") || name.startsWith("assurance.") || name.startsWith("scm.")
                || name.equals("evidence.store") || name.equals("evidence.create_manifest");
    }

    private static void validateHostBindings(Invocation invocation, JsonNode document) {
        requireHostBinding(document, "role", invocation.role());
        requireHostBinding(document, "source_commit", invocation.sourceCommit());
    }

    private static void requireHostBinding(JsonNode document, String field, String expected) {
        JsonNode actual = document.path(field);
        if (!actual.isMissingNode() && (!actual.isTextual() || !expected.equals(actual.asText()))) {
            throw new MultiAgentContractValidator.ContractValidationException(
                    "agent-runtime-host-binding", field + " is not bound to the host invocation");
        }
    }

    private static TaskUsageLedger.Lane usageLane(String role) {
        return "independent-reviewer".equals(role)
                ? TaskUsageLedger.Lane.FINALIZATION : TaskUsageLedger.Lane.STANDARD;
    }

    public record Invocation(String taskId, String attemptId, String sourceCommit, String role, String promptName,
                             String outputContract, Set<String> allowedTools, Set<String> allowedReferenceIds,
                             String untrustedInput, AgentToolLoop.Budget budget, String executionMode,
                             String traceId, String runId, String delegationId, String agentRunId) {
        public Invocation(String taskId, String attemptId, String sourceCommit, String role, String promptName,
                          String outputContract, Set<String> allowedTools, Set<String> allowedReferenceIds,
                          String untrustedInput, AgentToolLoop.Budget budget, String executionMode) {
            this(taskId, attemptId, sourceCommit, role, promptName, outputContract, allowedTools,
                    allowedReferenceIds, untrustedInput, budget, executionMode,
                    ExecutionIdentity.deterministic(taskId, attemptId, role, role));
        }

        private Invocation(String taskId, String attemptId, String sourceCommit, String role, String promptName,
                           String outputContract, Set<String> allowedTools, Set<String> allowedReferenceIds,
                           String untrustedInput, AgentToolLoop.Budget budget, String executionMode,
                           ExecutionIdentity identity) {
            this(taskId, attemptId, sourceCommit, role, promptName, outputContract, allowedTools,
                    allowedReferenceIds, untrustedInput, budget, executionMode, identity.traceId(), identity.runId(),
                    identity.delegationId(), identity.agentRunId());
        }
        public Invocation(String taskId, String attemptId, String sourceCommit, String role, String promptName,
                          String outputContract, Set<String> allowedTools, Set<String> allowedReferenceIds,
                          String untrustedInput, AgentToolLoop.Budget budget) {
            this(taskId, attemptId, sourceCommit, role, promptName, outputContract, allowedTools,
                    allowedReferenceIds, untrustedInput, budget, "HIERARCHICAL_ACTIVE");
        }

        public Invocation {
            if (taskId == null || taskId.isBlank() || attemptId == null || attemptId.isBlank()
                    || sourceCommit == null || !sourceCommit.matches("[0-9a-f]{40}")
                    || role == null || role.isBlank() || promptName == null || promptName.isBlank()
                    || outputContract == null || outputContract.isBlank() || untrustedInput == null || budget == null
                    || !OperationalKillSwitch.EXECUTION_MODES.contains(executionMode)
                    || "PIPELINE".equals(executionMode)) {
                throw new IllegalArgumentException("Agent invocation requires explicit identity, prompt, contract and budget");
            }
            allowedTools = Set.copyOf(allowedTools);
            allowedReferenceIds = Set.copyOf(allowedReferenceIds);
            new ExecutionIdentity(traceId, runId, delegationId, agentRunId);
        }

        public ExecutionIdentity executionIdentity() {
            return new ExecutionIdentity(traceId, runId, delegationId, agentRunId);
        }
    }

    public record Result(JsonNode document, String promptFingerprint, int turns, int tokens, long costMicros) { }
}
