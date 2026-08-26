package com.example.aifactory.service;

import com.example.aifactory.config.AiFactoryProperties;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Service
public class SandboxService {
    private final AiFactoryProperties props;
    private final ProcessRunner runner;

    public SandboxService(AiFactoryProperties props, ProcessRunner runner) {
        this.props = props;
        this.runner = runner;
    }

    private String execute(Path workspace, String network, String script, Duration timeout) throws Exception {
        return execute(workspace, network, script, List.of(), timeout);
    }

    private String execute(Path workspace, String network, String script, List<String> environment, Duration timeout) throws Exception {
        String taskDirectory = workspace.getFileName().toString();
        List<String> cmd = new ArrayList<>(List.of(
                "docker", "run", "--rm",
                "--network", network,
                "--memory", "2g",
                "--cpus", "2",
                "--pids-limit", "512",
                "--cap-drop", "ALL",
                "--security-opt", "no-new-privileges",
                "-v", props.workspaceVolume() + ":/factory-tasks",
                "-v", "ai-factory-m2:/root/.m2",
                "-w", "/factory-tasks/" + taskDirectory));
        for (String variable : environment) cmd.addAll(List.of("-e", variable));
        cmd.addAll(List.of(props.sandboxImage(), "bash", "-lc", script));
        return runner.run(cmd, null, timeout);
    }

    public String applyPatch(Path workspace) throws Exception {
        return execute(workspace, "none",
                "git apply --check changes.patch && git apply changes.patch && git diff --check && git diff --stat",
                Duration.ofMinutes(3));
    }

    public String checkPatch(Path workspace) throws Exception {
        return execute(workspace, "none", "git apply --check changes.patch", Duration.ofMinutes(3));
    }

    public String test(Path workspace) throws Exception {
        return execute(workspace, props.sandboxNetwork(),
                "if [ -f mvnw ]; then chmod +x mvnw; ./mvnw -B -s /opt/ai-factory/maven-settings.xml test; " +
                        "elif [ -f pom.xml ]; then mvn -B -s /opt/ai-factory/maven-settings.xml test; " +
                        "elif [ -f gradlew ]; then chmod +x gradlew; ./gradlew test; " +
                        "elif [ -f package.json ]; then npm test -- --runInBand; " +
                        "else echo 'No supported build file found'; exit 2; fi",
                mavenEnvironment(),
                Duration.ofMinutes(15));
    }

    public String quality(Path workspace) throws Exception {
        if (props.sonarToken() == null || props.sonarToken().isBlank()) {
            return "Skipped because AI_FACTORY_SONAR_TOKEN is not configured.";
        }
        return execute(workspace, props.sandboxNetwork(),
                "if [ -f pom.xml ]; then mkdir -p .ai-factory && set -o pipefail && " +
                        "mvn -B -s /opt/ai-factory/maven-settings.xml " +
                        "org.sonarsource.scanner.maven:sonar-maven-plugin:sonar " +
                        "-Dsonar.host.url=\"$SONAR_HOST_URL\" -Dsonar.token=\"$SONAR_TOKEN\" " +
                        "| tee .ai-factory/sonar.txt; " +
                        "else echo 'Skipped: SonarQube analysis currently supports Maven repositories only.'; fi",
                List.of("SONAR_HOST_URL=" + props.sonarqubeUrl(), "SONAR_TOKEN=" + props.sonarToken(),
                        "MAVEN_MIRROR_URL=" + props.mavenMirrorUrl(),
                        "ARTIFACTORY_TOKEN=" + props.artifactoryToken()),
                Duration.ofMinutes(15));
    }

    public String security(Path workspace) throws Exception {
        return execute(workspace, props.sandboxNetwork(),
                "mkdir -p .ai-factory && " +
                        "syft dir:. -o cyclonedx-json=.ai-factory/sbom.cdx.json >/dev/null && " +
                    "trivy fs --scanners vuln,secret --severity HIGH,CRITICAL --exit-code 0 --format table . | tee .ai-factory/trivy.txt && " +
                        "echo 'SBOM: .ai-factory/sbom.cdx.json'",
                Duration.ofMinutes(10));
    }

    private List<String> mavenEnvironment() {
        return List.of("MAVEN_MIRROR_URL=" + props.mavenMirrorUrl(),
                "ARTIFACTORY_TOKEN=" + props.artifactoryToken());
    }
}
