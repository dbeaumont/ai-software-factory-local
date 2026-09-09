package com.example.aifactory.agentruntime;

import com.example.aifactory.agentcore.AgentContractValidator;
import com.example.aifactory.agentcore.AgentLoop;
import com.example.aifactory.agentcore.LlmCompletionPort;
import com.example.aifactory.agentcore.McpToolPort;
import com.example.aifactory.agentcore.RoleScopedAgentContext;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Worker-side execution service consumed by the A2A server transport. */
public final class AgentExecutionWorker {
    private static final tools.jackson.databind.ObjectMapper JSON = new tools.jackson.databind.ObjectMapper();
    private final RoleScopedAgentContext role;
    private final LlmCompletionPort llm;
    private final McpToolPort mcp;
    private final A2aSpanLinks spanLinks;

    AgentExecutionWorker(RoleScopedAgentContext role, LlmCompletionPort llm, McpToolPort mcp) {
        this(role, llm, mcp, A2aSpanLinks.disabled());
    }

    AgentExecutionWorker(RoleScopedAgentContext role, LlmCompletionPort llm, McpToolPort mcp,
                         A2aSpanLinks spanLinks) {
        this.role = role;
        this.llm = llm;
        this.mcp = mcp;
        this.spanLinks = spanLinks;
    }

    public Result execute(Request request) {
        return spanLinks.call("ai.factory.a2a.agent.execute", "task-to-agent-execution", request.traceparent(),
                java.util.Map.of("ai_factory.task.id", request.taskId(), "ai_factory.attempt.id", request.attemptId(),
                        "a2a.agent.role", request.role()), () -> executeLinked(request));
    }

    private Result executeLinked(Request request) {
        role.requireActiveRole(request.role());
        AgentContractValidator.Context contractContext = new AgentContractValidator.Context(
                request.taskId(), request.attemptId(), request.admittedReferences().keySet());
        role.validateInput(request.inputContract(), request.input(), contractContext);
        Set<String> allowedTools = allowedTools(request);
        List<LlmCompletionPort.ToolDefinition> toolDefinitions = mcp.definitions().stream()
                .filter(definition -> allowedTools.contains(definition.name())).toList();
        AgentLoop loop = new AgentLoop(
                messages -> llm.nextTurn(messages, toolDefinitions, outputTokenLimit(request)),
                call -> mcp.call(call.name(), call.arguments()),
                (actor, tool) -> allowedTools.contains(tool),
                AgentLoop.SafetyLimits.defaults(), ignored -> { });
        boolean pipelineCompatibility = "pipeline-agent-task-v1".equals(request.inputContract());
        String agentInput = pipelineCompatibility ? request.input().path("payload").asText() : request.input().toString();
        java.util.concurrent.atomic.AtomicReference<String> acceptedFinal =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.Callable<AgentLoop.Result> agentLoop = () -> {
            try {
                return loop.run(new AgentLoop.Actor(request.taskId(), role.identity().role()),
                        systemPrompt(request), agentInput, request.budget(), finalResult -> {
                            if (pipelineCompatibility) return;
                            try {
                                String bound = PatchProposalBinder.bind(JSON, request, finalResult);
                                role.validateOutput(request.outputContract(), bound, contractContext);
                                acceptedFinal.set(bound);
                            } catch (IllegalArgumentException invalid) {
                                throw new AgentLoop.ContractFeedbackException(invalid.getMessage(), invalid);
                            }
                        });
            } catch (AgentLoop.ContractFeedbackException invalid) {
                if (invalid.getCause() instanceof AgentContractValidator.ContractValidationException contract) {
                    throw contract;
                }
                throw invalid;
            }
        };
        AgentMcpExecutionContext mcpContext = new AgentMcpExecutionContext(
                request.taskId(), request.attemptId(), request.input().path("source_commit").asText(),
                Instant.now().plus(request.budget().deadline()));
        java.util.concurrent.Callable<AgentLoop.Result> invocation = () -> mcpContext.call(agentLoop);
        AgentLoop.Result result;
        try {
            result = request.traceparent() == null ? invocation.call()
                    : new A2aW3cTraceContext(request.traceparent(), request.baggage()).call(invocation);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("Agent execution failed", failure);
        }
        JsonNode document;
        if (pipelineCompatibility) {
            var wrapped = tools.jackson.databind.node.JsonNodeFactory.instance.objectNode();
            wrapped.put("schema_version", "1");
            wrapped.put("task_id", request.taskId());
            wrapped.put("attempt_id", request.attemptId());
            wrapped.put("role", request.role());
            wrapped.put("operation", request.input().path("operation").asText());
            wrapped.put("status", "COMPLETED");
            wrapped.put("content", result.finalResult());
            wrapped.put("prompt_fingerprint", role.promptFingerprint(request.inputContract()));
            wrapped.put("turns", result.turns());
            wrapped.put("tokens", result.tokens());
            wrapped.put("cost_micros", result.costMicros());
            document = role.validateOutput(request.outputContract(), wrapped, contractContext);
        } else {
            document = role.validateOutput(request.outputContract(), acceptedFinal.get(), contractContext);
        }
        return new Result(document, role.promptFingerprint(request.inputContract()), result.turns(), result.tokens(), result.costMicros());
    }

    private Set<String> allowedTools(Request request) {
        JsonNode taskTools = request.input().path("allowed_tools");
        if (!taskTools.isArray()) return role.allowedTools();
        LinkedHashSet<String> allowed = new LinkedHashSet<>();
        taskTools.forEach(tool -> allowed.add(tool.asText()));
        if (!role.allowedTools().containsAll(allowed)) {
            throw new SecurityException("Specialist task grants a tool outside the role manifest");
        }
        return Set.copyOf(allowed);
    }

    private String systemPrompt(Request request) {
        StringBuilder prompt = new StringBuilder(role.systemPrompt(request.inputContract()))
                .append("\n\n## Contrat de sortie immuable\n\n")
                .append("Retourne exclusivement un document `")
                .append(request.outputContract())
                .append("` dont `schema_version` est exactement la chaine JSON `\"1\"` ")
                .append("(et jamais le nombre `1`).\n");
        if ("delegation-plan-v1".equals(request.outputContract())) {
            JsonNode input = request.input();
            prompt.append("\n\n## Binding immuable du plan de delegation\n\n")
                    .append("Recopie exactement les valeurs d'entree suivantes dans la sortie :\n")
                    .append("- `plan_id` = `").append(input.path("delegation_plan_id").asText()).append("`\n")
                    .append("- `task_id` = `").append(request.taskId()).append("`\n")
                    .append("- `attempt_id` = `").append(request.attemptId()).append("`\n")
                    .append("- `source_commit` = `").append(input.path("source_commit").asText()).append("`\n")
                    .append("- `risk_class` = `").append(input.path("risk_class").asText()).append("`\n")
                    .append("Toute valeur `risks[].level` est exclusivement l'une de `R0`, `R1`, `R2`, `R3`, `R4`.\n");
            if ("short-plan".equals(input.path("node_id").asText())) {
                prompt.append("Pour ce chemin court, utilise `risks` = `[]` et produis exactement un noeud : ")
                        .append("son `role` vaut `developer`, son `parent_node_id` vaut `null`, ")
                        .append("son `depends_on` vaut `[]` et son `scope.repository_id` vaut `")
                        .append(input.path("scope").path("repository_id").asText()).append("`.\n")
                        .append("Chaque plafond du `budget` du noeud doit etre inferieur ou egal au plafond homonyme de l'entree.\n")
                        .append("Reponds immediatement avec un objet de cette forme exacte, sans autre propriete ni texte :\n")
                        .append(shortPlanShape(request));
            }
        }
        if ("patch-proposal-v1".equals(request.outputContract())) {
            prompt.append("\n\n## Production obligatoire du patch\n\n")
                    .append("Lis d'abord avec les outils `context.*` les fichiers necessaires dans les chemins autorises. ")
                    .append("La sortie finale doit contenir un vrai diff unifie applicable, jamais une explication, ")
                    .append("un refus, un blocage ou un exemple. Le champ `patch` commence exactement par ")
                    .append("`diff --git a/<chemin> b/<chemin>` et inclut `---`, `+++` et au moins un hunk `@@`.\n")
                    .append("L'hote derive et remplace les metadonnees de securite. ")
                    .append("Tu peux donc retourner cette forme JSON minimale exacte, sans bloc Markdown :\n")
                    .append("{\"proposal_id\":\"proposal-1\",\"patch\":\"diff --git a/<chemin> b/<chemin>\\n")
                    .append("--- a/<chemin>\\n+++ b/<chemin>\\n@@ -1 +1 @@\\n-ancienne ligne\\n+nouvelle ligne\\n\",")
                    .append("\"summary\":\"description concise\"}\n")
                    .append("Remplace tous les marqueurs par le chemin et le contenu exacts lus dans le depot.\n");
        }
        if (!request.admittedReferences().isEmpty()) {
            prompt.append("\n\n## Contexte d'admission immuable\n\n")
                    .append("Toute citation de la sortie doit reprendre exactement un couple autorise ci-dessous. ")
                    .append("Utilise `kind` = `EVIDENCE`. N'invente ni identifiant ni digest.\n");
            request.admittedReferences().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(reference -> prompt.append("- reference_id=`")
                            .append(reference.getKey()).append("`, digest=`")
                            .append(reference.getValue()).append("`\n"));
        }
        return prompt.toString();
    }

    private static int outputTokenLimit(Request request) {
        int contractLimit = "delegation-plan-v1".equals(request.outputContract()) ? 4_096 : 8_192;
        return Math.min(request.budget().maxTokens(), contractLimit);
    }

    private static String shortPlanShape(Request request) {
        JsonNode input = request.input();
        Map.Entry<String, String> citation = request.admittedReferences().entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).findFirst().orElseThrow(
                        () -> new IllegalArgumentException("Short plan requires one admitted input reference"));
        return "{" +
                "\"schema_version\":\"1\"," +
                "\"plan_id\":" + input.path("delegation_plan_id") + "," +
                "\"task_id\":\"" + request.taskId() + "\"," +
                "\"attempt_id\":\"" + request.attemptId() + "\"," +
                "\"source_commit\":" + input.path("source_commit") + "," +
                "\"risk_class\":" + input.path("risk_class") + "," +
                "\"root_role\":\"supervisor\"," +
                "\"citations\":[{\"reference_id\":\"" + citation.getKey() +
                "\",\"kind\":\"EVIDENCE\",\"digest\":\"" + citation.getValue() + "\"}]," +
                "\"assumptions\":[],\"risks\":[]," +
                "\"nodes\":[{\"node_id\":\"developer-short-plan\",\"role\":\"developer\"," +
                "\"parent_node_id\":null,\"depends_on\":[]," +
                "\"objective\":" + input.path("objective") + "," +
                "\"scope\":{\"repository_id\":" + input.path("scope").path("repository_id") +
                ",\"read_paths\":[\".\"],\"write_paths\":[\".\"]}," +
                "\"budget\":" + input.path("budget") + "," +
                "\"success_criteria\":" + input.path("success_criteria") + "," +
                "\"stop_condition\":\"SUCCESS_CRITERIA_MET\"}]," +
                "\"created_at\":" + input.path("issued_at") + "}\n";
    }

    public record Request(String taskId, String attemptId, String role, String inputContract, JsonNode input,
                          String outputContract, Map<String, String> admittedReferences, AgentLoop.Budget budget,
                          String traceparent, String baggage) {
        public Request {
            if (taskId == null || taskId.isBlank() || attemptId == null || attemptId.isBlank()
                    || role == null || role.isBlank() || inputContract == null || inputContract.isBlank()
                    || input == null || outputContract == null || outputContract.isBlank() || budget == null) {
                throw new IllegalArgumentException("Agent execution request is incomplete");
            }
            admittedReferences = admittedReferences == null ? Map.of() : Map.copyOf(admittedReferences);
            if (admittedReferences.entrySet().stream().anyMatch(entry -> entry.getKey() == null
                    || !entry.getKey().matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")
                    || entry.getValue() == null || !entry.getValue().matches("[0-9a-f]{64}"))) {
                throw new IllegalArgumentException("Admitted Evidence references are invalid");
            }
        }
    }

    public record Result(JsonNode document, String promptFingerprint, int turns, int tokens, long costMicros) {}
}
