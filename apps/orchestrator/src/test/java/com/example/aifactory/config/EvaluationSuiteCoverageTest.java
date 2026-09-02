package com.example.aifactory.config;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class EvaluationSuiteCoverageTest {
    private static final Path RESOURCES = Path.of(System.getProperty("user.dir"))
            .resolve("../../resources/multiagents").normalize();

    @Test
    @SuppressWarnings("unchecked")
    void providesRepresentativePairedCasesForEveryMandatoryCohort() throws Exception {
        Map<String, String> filesByCohort = new LinkedHashMap<>();
        filesByCohort.put("SIMPLE_SHORT_PATH", "short-path-cases-v1.json");
        filesByCohort.put("MULTI_DOMAIN_HIERARCHICAL", "multi-domain-cases-v1.json");
        filesByCohort.put("ADVERSARIAL", "adversarial-cases-v1.json");
        filesByCohort.put("RECOVERY_AND_RETRY", "recovery-cases-v1.json");
        Map<String, Object> policy = new Yaml().load(Files.readString(
                RESOURCES.resolve("policies/qualification-thresholds-v1.yaml")));
        Map<String, Object> sample = (Map<String, Object>) policy.get("sample");
        Map<String, Object> minima = (Map<String, Object>) sample.get("minimumCasesByCohort");
        ObjectMapper mapper = new ObjectMapper();
        Set<String> caseIds = new HashSet<>();
        Set<String> ecosystems = new HashSet<>();
        int total = 0;

        for (Map.Entry<String, String> cohort : filesByCohort.entrySet()) {
            JsonNode cases = mapper.readTree(Files.readString(
                    RESOURCES.resolve("evaluations").resolve(cohort.getValue()))).path("cases");
            assertThat(cases.size()).as(cohort.getKey())
                    .isGreaterThanOrEqualTo(((Number) minima.get(cohort.getKey())).intValue());
            total += cases.size();
            for (JsonNode sampleCase : cases) {
                assertThat(caseIds.add(sampleCase.path("id").asText())).as("unique case id").isTrue();
                if (sampleCase.path("ecosystem").isTextual()) {
                    ecosystems.add(sampleCase.path("ecosystem").asText());
                }
            }
        }

        assertThat(total).isGreaterThanOrEqualTo(((Number) sample.get("minimumPairedCases")).intValue());
        assertThat(ecosystems).hasSizeGreaterThanOrEqualTo(
                ((Number) sample.get("minimumEcosystems")).intValue());
    }
}
