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
                "-w", "/factory-tasks/" + taskDirectory,
                props.sandboxImage(),
                "bash", "-lc", script));
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
        return execute(workspace, "ai-factory-local",
                "if [ -f mvnw ]; then chmod +x mvnw; ./mvnw -B test; " +
                        "elif [ -f pom.xml ]; then mvn -B test; " +
                        "elif [ -f gradlew ]; then chmod +x gradlew; ./gradlew test; " +
                        "elif [ -f package.json ]; then npm test -- --runInBand; " +
                        "else echo 'No supported build file found'; exit 2; fi",
                Duration.ofMinutes(15));
    }

    public String security(Path workspace) throws Exception {
        return execute(workspace, "ai-factory-local",
                "mkdir -p .ai-factory && " +
                        "syft dir:. -o cyclonedx-json=.ai-factory/sbom.cdx.json >/dev/null && " +
                        "trivy fs --skip-db-update --scanners vuln,secret --severity HIGH,CRITICAL --exit-code 0 --format table . | tee .ai-factory/trivy.txt && " +
                        "echo 'SBOM: .ai-factory/sbom.cdx.json'",
                Duration.ofMinutes(10));
    }
}
