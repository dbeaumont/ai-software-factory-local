package com.example.aifactory.sandbox.service;

import com.example.aifactory.sandbox.model.SandboxModels.Operation;

import java.time.Duration;
import java.util.List;

final class SandboxProfiles {
    private SandboxProfiles() {
    }

    static Profile forOperation(Operation operation) {
        return switch (operation) {
            case VALIDATE_PATCH -> new Profile("patch-check-v1", "none", Duration.ofMinutes(3),
                    "git apply --check changes.patch", List.of(), true, false);
            case APPLY_PATCH -> new Profile("patch-apply-v1", "none", Duration.ofMinutes(3),
                    "git apply --check changes.patch && git apply changes.patch && git diff --check && git diff --stat",
                    List.of(), false, false);
            case RUN_TESTS -> new Profile("test-auto-v1", "factory", Duration.ofMinutes(15),
                    "if [ -f mvnw ]; then chmod +x mvnw; ./mvnw -B -s /opt/ai-factory/maven-settings.xml test; " +
                            "elif [ -f pom.xml ]; then mvn -B -s /opt/ai-factory/maven-settings.xml test; " +
                            "elif [ -f gradlew ]; then chmod +x gradlew; ./gradlew test; " +
                            "elif [ -f package.json ]; then npm test -- --runInBand; " +
                            "else echo 'No supported build file found'; exit 2; fi",
                    List.of("MAVEN_MIRROR_URL", "ARTIFACTORY_TOKEN"), false, true);
            case RUN_QUALITY -> new Profile("quality-sonar-v1", "factory", Duration.ofMinutes(15),
                    "if [ -z \"$SONAR_TOKEN\" ]; then echo 'Required Sonar token is unavailable'; exit 2; " +
                            "elif [ -f pom.xml ]; then mkdir -p .ai-factory && set -o pipefail && " +
                            "mvn -B -s /opt/ai-factory/maven-settings.xml " +
                            "org.sonarsource.scanner.maven:sonar-maven-plugin:sonar " +
                            "-Dsonar.host.url=\"$SONAR_HOST_URL\" -Dsonar.token=\"$SONAR_TOKEN\" " +
                            "-Dsonar.qualitygate.wait=true | tee .ai-factory/sonar.txt; " +
                            "else echo 'SonarQube analysis supports Maven repositories only'; exit 2; fi",
                    List.of("SONAR_HOST_URL", "SONAR_TOKEN", "MAVEN_MIRROR_URL", "ARTIFACTORY_TOKEN"),
                    false, true);
            case RUN_SECURITY -> new Profile("security-syft-trivy-v1", "factory", Duration.ofMinutes(10),
                    "set -o pipefail && mkdir -p .ai-factory && " +
                            "syft dir:. -o cyclonedx-json=.ai-factory/sbom.cdx.json >/dev/null && " +
                            "trivy fs --scanners vuln,secret --severity HIGH,CRITICAL --exit-code 1 --format table . " +
                            "| tee .ai-factory/trivy.txt && echo 'SBOM: .ai-factory/sbom.cdx.json'",
                    List.of(), false, false);
        };
    }

    record Profile(String id, String network, Duration timeout, String script, List<String> environmentNames,
                   boolean workspaceReadOnly, boolean mavenCache) {
    }
}
