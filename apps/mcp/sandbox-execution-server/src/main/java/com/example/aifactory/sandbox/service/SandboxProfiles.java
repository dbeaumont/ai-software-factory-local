package com.example.aifactory.sandbox.service;

import com.example.aifactory.sandbox.model.SandboxModels.Operation;

import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
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
            case RUN_TESTS -> throw new IllegalArgumentException("test profile selection requires a workspace");
            case RUN_QUALITY -> new Profile("quality-sonar-v1", "quality", Duration.ofMinutes(15),
                    "if [ -z \"$SONAR_TOKEN\" ]; then echo 'Required Sonar token is unavailable'; exit 2; " +
                            "elif [ -f pom.xml ]; then mkdir -p .ai-factory && set -o pipefail && " +
                            "if [ -n \"$ARTIFACTORY_TOKEN\" ]; then " +
                            "MAVEN_SETTINGS='-s /opt/ai-factory/maven-settings.xml'; else " +
                            "MAVEN_SETTINGS='-s /opt/ai-factory/maven-settings-public.xml'; fi; mvn -B $MAVEN_SETTINGS " +
                            "org.sonarsource.scanner.maven:sonar-maven-plugin:sonar " +
                            "-Dsonar.host.url=\"$SONAR_HOST_URL\" -Dsonar.token=\"$SONAR_TOKEN\" " +
                            "-Dsonar.qualitygate.wait=true | tee .ai-factory/sonar.txt; " +
                            "else echo 'SonarQube analysis supports Maven repositories only'; exit 2; fi",
                    List.of("SONAR_HOST_URL", "SONAR_TOKEN", "MAVEN_MIRROR_URL", "ARTIFACTORY_TOKEN",
                            "MAVEN_OPTS", "MAVEN_PROXY_HOST", "MAVEN_NO_PROXY_HOSTS",
                            "HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY"),
                    false, true);
            case RUN_SECURITY -> new Profile("security-syft-trivy-v2", "factory", Duration.ofMinutes(10),
                    "set -o pipefail && mkdir -p .ai-factory && " +
                            "syft dir:. -o cyclonedx-json=.ai-factory/sbom.cdx.json >/dev/null && " +
                            "for attempt in 1 2 3; do trivy fs --download-db-only --timeout 2m && break; " +
                            "if [ \"$attempt\" -eq 3 ]; then exit 2; fi; sleep \"$attempt\"; done && " +
                            "trivy fs --skip-db-update --scanners vuln,secret --severity HIGH,CRITICAL " +
                            "--exit-code 1 --format table . " +
                            "| tee .ai-factory/trivy.txt && echo 'SBOM: .ai-factory/sbom.cdx.json'",
                    List.of("HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY", "SYFT_CHECK_FOR_APP_UPDATE",
                            "TRIVY_NO_PROGRESS"), false, false);
        };
    }

    static Profile forOperation(Operation operation, Path workspace) {
        if (operation != Operation.RUN_TESTS) {
            return forOperation(operation);
        }
        if (regular(workspace, "mvnw") || regular(workspace, "pom.xml")) {
            return new Profile("test-maven-v1", "factory", Duration.ofMinutes(15),
                    "if [ -z \"$MAVEN_MIRROR_URL\" ]; then echo 'Required Maven mirror is unavailable'; exit 2; fi; " +
                            "if [ -n \"$ARTIFACTORY_TOKEN\" ]; then " +
                            "MAVEN_SETTINGS='-s /opt/ai-factory/maven-settings.xml'; else " +
                            "MAVEN_SETTINGS='-s /opt/ai-factory/maven-settings-public.xml'; fi; " +
                            "if [ -f mvnw ]; then chmod +x mvnw; ./mvnw -B $MAVEN_SETTINGS test; " +
                            "else mvn -B $MAVEN_SETTINGS test; fi",
                    List.of("MAVEN_MIRROR_URL", "ARTIFACTORY_TOKEN", "MAVEN_OPTS", "MAVEN_PROXY_HOST",
                            "MAVEN_NO_PROXY_HOSTS",
                            "HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY"),
                    false, true);
        }
        if (regular(workspace, "gradlew") || regular(workspace, "build.gradle")
                || regular(workspace, "build.gradle.kts")) {
            return new Profile("test-gradle-v1", "factory", Duration.ofMinutes(15),
                    "if [ ! -f gradlew ]; then echo 'The immutable Gradle profile requires gradlew'; exit 2; fi; " +
                            "if [ -z \"$MAVEN_MIRROR_URL\" ]; then echo 'Required Gradle dependency mirror is unavailable'; exit 2; fi; " +
                            "chmod +x gradlew; ./gradlew --no-daemon " +
                            "--init-script /opt/ai-factory/gradle-mirror.init.gradle test",
                    List.of("MAVEN_MIRROR_URL", "ARTIFACTORY_TOKEN", "GRADLE_OPTS", "NO_PROXY"), false, false);
        }
        if (regular(workspace, "package.json")) {
            return new Profile("test-node-v1", "factory", Duration.ofMinutes(15),
                    "if [ -z \"$NPM_CONFIG_REGISTRY\" ]; then echo 'Required npm registry is unavailable'; exit 2; " +
                            "elif [ -f package-lock.json ]; then npm ci --ignore-scripts; " +
                            "else echo 'The immutable Node profile requires package-lock.json'; exit 2; fi; " +
                            "npm test -- --runInBand",
                    List.of("NPM_CONFIG_REGISTRY", "HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY"), false, false);
        }
        throw new IllegalArgumentException("no supported immutable test profile for workspace");
    }

    private static boolean regular(Path workspace, String name) {
        return Files.isRegularFile(workspace.resolve(name), LinkOption.NOFOLLOW_LINKS);
    }

    record Profile(String id, String network, Duration timeout, String script, List<String> environmentNames,
                   boolean workspaceReadOnly, boolean mavenCache) {
    }
}
