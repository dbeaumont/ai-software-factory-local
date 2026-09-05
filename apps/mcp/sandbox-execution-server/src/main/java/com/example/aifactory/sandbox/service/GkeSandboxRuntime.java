package com.example.aifactory.sandbox.service;

import com.example.aifactory.sandbox.config.SandboxExecutionProperties;
import com.example.aifactory.sandbox.model.SandboxModels.Operation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.Set;

import static com.example.aifactory.sandbox.service.GkeJobController.JobRequest;

/**
 * Target adapter for GKE Jobs or Agent Sandbox. Tool contracts and registered profiles remain unchanged.
 */
@Service
@ConditionalOnProperty(prefix = "ai-factory.sandbox", name = "runtime", havingValue = "gke")
@ConditionalOnBean(GkeJobController.class)
public class GkeSandboxRuntime implements SandboxRuntime {
    private final SandboxExecutionProperties properties;
    private final GkeJobController controller;

    public GkeSandboxRuntime(SandboxExecutionProperties properties, GkeJobController controller) {
        this.properties = properties;
        this.controller = controller;
    }

    @Override
    public RuntimeResult execute(Operation operation, String executionId, Path workspace) throws Exception {
        SandboxProfiles.Profile profile = SandboxProfiles.forOperation(operation, workspace);
        JobRequest request = new JobRequest(
                executionId,
                operation.name(),
                profile.id(),
                workspace.getFileName().toString(),
                properties.image(),
                properties.imageDigest(),
                networkPolicy(profile.network()),
                "2",
                "2Gi",
                512,
                profile.timeout(),
                profile.workspaceReadOnly(),
                profile.mavenCache(),
                profile.environmentNames());
        return controller.run(request);
    }

    @Override
    public void cancel(String executionId) {
        controller.cancel(executionId);
    }

    @Override
    public void reconcileOrphans(Set<String> retainedExecutionIds) throws Exception {
        controller.reconcileOrphans(retainedExecutionIds);
    }

    private static String networkPolicy(String network) {
        return switch (network) {
            case "none" -> "sandbox-deny-all";
            case "quality" -> "sandbox-quality-egress";
            default -> "sandbox-dependency-egress";
        };
    }
}
