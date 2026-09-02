package com.example.aifactory.service;

import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Host-owned planner for cross-domain or materially uncertain changes. */
@Component
public final class HierarchicalPathPlanner {
    private static final String RESOURCE = "multiagents/policies/routing-policy-v1.yaml";
    private static final List<String> SPECIALIST_AGENTS = List.of(
            "architecture-agent", "code-agent", "test-agent", "security-agent", "independent-reviewer");
    private static final List<Stage> STAGES = List.of(
            new Stage("architecture", "architecture-agent", "supervisor", Set.of()),
            new Stage("code", "code-agent", "supervisor", Set.of("architecture")),
            new Stage("tests", "test-agent", "supervisor", Set.of("code")),
            new Stage("security", "security-agent", "supervisor", Set.of("architecture", "code")),
            new Stage("independent-review", "independent-reviewer", "workflow",
                    Set.of("code", "tests", "security")));
    private final String policyId;
    private final String version;
    private final Set<String> allowedRisks;
    private final int minModules;
    private final int minDomains;
    private final int minIndependentCodeScopes;
    private final Set<String> triggeringImpacts;
    private final boolean materialDecisionTriggers;
    private final Map<String, Map<String, Object>> modeCeilings;

    public HierarchicalPathPlanner() {
        Map<String, Object> policy = load();
        policyId = required(policy, "policyId");
        version = required(policy, "version");
        Map<String, Object> hierarchical = map(policy.get("hierarchicalPath"), "hierarchicalPath");
        allowedRisks = strings(hierarchical, "allowedRisks");
        Map<String, Object> selection = map(hierarchical.get("selectWhenAny"), "selectWhenAny");
        minModules = number(selection, "minModules");
        minDomains = number(selection, "minDomains");
        minIndependentCodeScopes = number(selection, "independentCodeScopes");
        triggeringImpacts = strings(selection, "impacts");
        materialDecisionTriggers = Boolean.TRUE.equals(selection.get("materialDecisionOpen"));
        Map<String, Object> rawModes = map(policy.get("modeCeilings"), "modeCeilings");
        java.util.LinkedHashMap<String, Map<String, Object>> parsedModes = new java.util.LinkedHashMap<>();
        rawModes.forEach((mode, value) -> parsedModes.put(mode, map(value, "mode " + mode)));
        modeCeilings = Map.copyOf(parsedModes);
    }

    public Optional<Plan> plan(Input input) {
        if (!eligibleInput(input)) return Optional.empty();
        Map<String, Object> ceiling = modeCeilings.get(input.mode());
        if (ceiling == null || !strings(ceiling, "allowedPaths").contains("HIERARCHICAL_PATH")
                || "PIPELINE_BASELINE".equals(ceiling.get("authoritativePath"))) {
            return Optional.empty();
        }
        if (ceiling.containsKey("requiresQualification")
                && !ceiling.get("requiresQualification").toString().equals(input.qualification())) {
            return Optional.empty();
        }
        if (Boolean.TRUE.equals(ceiling.get("requiresRepositoryAllowlist")) && !input.repositoryAllowlisted()) {
            return Optional.empty();
        }
        if (Boolean.TRUE.equals(ceiling.get("requiresStableCanaryBucket")) && !input.stableCanaryBucket()) {
            return Optional.empty();
        }
        boolean triggered = input.modules() >= minModules || input.domains() >= minDomains
                || input.independentCodeScopes() >= minIndependentCodeScopes
                || input.impacts().stream().anyMatch(triggeringImpacts::contains)
                || materialDecisionTriggers && input.materialDecisionOpen();
        if (!allowedRisks.contains(input.risk()) || !triggered) return Optional.empty();
        String humanGate = "R2".equals(input.risk()) ? "BEFORE_EXTERNAL_EFFECT" : "NONE";
        return Optional.of(new Plan(policyId, version, "HIERARCHICAL_PATH", "supervisor",
                SPECIALIST_AGENTS, STAGES, humanGate));
    }

    private static boolean eligibleInput(Input input) {
        return input != null && input.mode() != null && input.qualification() != null && input.risk() != null
                && input.modules() >= 0 && input.domains() >= 0 && input.independentCodeScopes() >= 0
                && input.inputsComplete() && !input.contradictory() && input.budgetAvailable();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> load() {
        try (InputStream input = HierarchicalPathPlanner.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (input == null) throw new IllegalStateException("Missing " + RESOURCE);
            return map(new Yaml(new SafeConstructor(new LoaderOptions())).load(input), "routing policy");
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot load routing policy", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value, String field) {
        if (!(value instanceof Map<?, ?>)) throw new IllegalStateException(field + " must be an object");
        return (Map<String, Object>) value;
    }

    private static String required(Map<String, Object> value, String field) {
        Object result = value.get(field);
        if (result == null || result.toString().isBlank()) throw new IllegalStateException(field + " is required");
        return result.toString();
    }

    private static int number(Map<String, Object> value, String field) {
        Object result = value.get(field);
        if (!(result instanceof Number number)) throw new IllegalStateException(field + " must be numeric");
        return number.intValue();
    }

    private static Set<String> strings(Map<String, Object> value, String field) {
        Object result = value.get(field);
        if (!(result instanceof List<?> values)) throw new IllegalStateException(field + " must be a list");
        Set<String> strings = new LinkedHashSet<>();
        values.forEach(item -> strings.add(item.toString()));
        return Set.copyOf(strings);
    }

    public record Input(String mode, String qualification, String risk, int modules, int domains,
                        int independentCodeScopes, Set<String> impacts, boolean materialDecisionOpen,
                        boolean repositoryAllowlisted, boolean stableCanaryBucket, boolean inputsComplete,
                        boolean contradictory, boolean budgetAvailable) {
        public Input {
            impacts = impacts == null ? Set.of() : Set.copyOf(impacts);
        }
    }

    public record Stage(String id, String role, String parentAuthority, Set<String> dependsOn) {
        public Stage {
            dependsOn = Set.copyOf(dependsOn);
        }
    }

    public record Plan(String policyId, String policyVersion, String path, String supervisor,
                       List<String> specialistAgents, List<Stage> stages, String humanGate) {
        public Plan {
            specialistAgents = List.copyOf(specialistAgents);
            stages = List.copyOf(stages);
        }
    }
}
