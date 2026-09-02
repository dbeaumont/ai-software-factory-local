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

/** Host-owned planner for the bounded single-developer path. */
@Component
public final class ShortCodePathPlanner {
    private static final String RESOURCE = "multiagents/policies/routing-policy-v1.yaml";
    private static final List<String> AGENTS = List.of("supervisor", "developer", "independent-reviewer");
    private static final Set<String> FORBIDDEN_AGENTS = Set.of("architecture-agent", "security-agent");
    private static final List<String> CONTROLS = List.of(
            "PATCH_VALIDATION", "TESTS", "QUALITY", "SECURITY", "EVIDENCE_MANIFEST");
    private final String policyId;
    private final String version;
    private final Set<String> allowedRisks;
    private final int maxModules;
    private final int maxDomains;
    private final int maxEstimatedFiles;
    private final Set<String> forbiddenImpacts;
    private final Map<String, Map<String, Object>> modeCeilings;

    public ShortCodePathPlanner() {
        Map<String, Object> policy = load();
        policyId = required(policy, "policyId");
        version = required(policy, "version");
        Map<String, Object> shortPath = map(policy.get("shortCodePath"), "shortCodePath");
        allowedRisks = strings(shortPath, "allowedRisks");
        maxModules = number(shortPath, "maxModules");
        maxDomains = number(shortPath, "maxDomains");
        maxEstimatedFiles = number(shortPath, "maxEstimatedFiles");
        forbiddenImpacts = strings(shortPath, "forbiddenImpacts");
        Map<String, Object> rawModes = map(policy.get("modeCeilings"), "modeCeilings");
        java.util.LinkedHashMap<String, Map<String, Object>> parsedModes = new java.util.LinkedHashMap<>();
        rawModes.forEach((mode, value) -> parsedModes.put(mode, map(value, "mode " + mode)));
        modeCeilings = Map.copyOf(parsedModes);
    }

    public Optional<Plan> plan(Input input) {
        if (input == null || input.mode() == null || input.qualification() == null || input.risk() == null
                || input.modules() < 0 || input.domains() < 0 || input.estimatedFiles() < 0
                || !input.inputsComplete() || input.contradictory() || !input.budgetAvailable()) {
            return Optional.empty();
        }
        Map<String, Object> ceiling = modeCeilings.get(input.mode());
        if (ceiling == null || !strings(ceiling, "allowedPaths").contains("SHORT_CODE_PATH")
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
        if (!allowedRisks.contains(input.risk()) || input.modules() > maxModules || input.domains() > maxDomains
                || input.estimatedFiles() > maxEstimatedFiles
                || input.impacts().stream().anyMatch(forbiddenImpacts::contains)) {
            return Optional.empty();
        }
        return Optional.of(new Plan(policyId, version, "SHORT_CODE_PATH", AGENTS, FORBIDDEN_AGENTS,
                CONTROLS, 1, "NONE"));
    }

    public String policyId() {
        return policyId;
    }

    public String policyVersion() {
        return version;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> load() {
        try (InputStream input = ShortCodePathPlanner.class.getClassLoader().getResourceAsStream(RESOURCE)) {
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
                        int estimatedFiles, Set<String> impacts, boolean repositoryAllowlisted,
                        boolean stableCanaryBucket, boolean inputsComplete, boolean contradictory,
                        boolean budgetAvailable) {
        public Input {
            impacts = impacts == null ? Set.of() : Set.copyOf(impacts);
        }
    }

    public record Plan(String policyId, String policyVersion, String path, List<String> agents,
                       Set<String> forbiddenAgents, List<String> deterministicControls,
                       int maxDeveloperDelegations, String humanGate) {
        public Plan {
            agents = List.copyOf(agents);
            forbiddenAgents = Set.copyOf(forbiddenAgents);
            deterministicControls = List.copyOf(deterministicControls);
        }
    }
}
