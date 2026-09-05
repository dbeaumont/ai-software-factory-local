package com.example.aifactory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

@ConfigurationProperties(prefix = "ai-factory.temporal")
public record TemporalProperties(String target, String namespace, Duration namespaceRetention,
                                 String deploymentName, String buildId,
                                 Map<String, String> taskQueues, Capacity capacity, Security security) {
    private static final Set<String> REQUIRED_QUEUES = Set.of(
            "workflow", "context", "llm", "sandbox", "assurance", "evidence", "scm");

    public TemporalProperties {
        taskQueues = taskQueues == null ? Map.of() : Map.copyOf(taskQueues);
        if (!validTarget(target)) {
            throw new IllegalArgumentException("Temporal target must be host:port");
        }
        if (namespace == null || !namespace.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalArgumentException("Temporal namespace is invalid");
        }
        if (namespaceRetention == null || namespaceRetention.compareTo(Duration.ofDays(1)) < 0
                || namespaceRetention.compareTo(Duration.ofDays(30)) > 0) {
            throw new IllegalArgumentException("Temporal namespace retention must be between 1 and 30 days");
        }
        if (deploymentName == null || !deploymentName.matches("[A-Za-z0-9._-]{1,64}")
                || buildId == null || !buildId.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new IllegalArgumentException("Temporal worker deployment identity is invalid");
        }
        if (!taskQueues.keySet().containsAll(REQUIRED_QUEUES)
                || taskQueues.values().stream().anyMatch(queue -> queue == null
                || !queue.matches("[a-z][a-z0-9-]{2,63}"))
                || taskQueues.values().stream().distinct().count() != taskQueues.size()) {
            throw new IllegalArgumentException("Temporal task queues are incomplete or invalid");
        }
        capacity = capacity == null ? Capacity.defaults() : capacity;
        security = security == null ? new Security(false, "", "", "", "") : security;
    }

    private static boolean validTarget(String target) {
        if (target == null || !target.matches("[A-Za-z0-9._-]+:[0-9]{1,5}")) return false;
        int port = Integer.parseInt(target.substring(target.lastIndexOf(':') + 1));
        return port >= 1 && port <= 65_535;
    }

    public record Capacity(int workflowCacheSize, int maxWorkflowThreads, int workflowTaskPollers,
                           int activityTaskPollers, int maxConcurrentWorkflowTasks,
                           int maxConcurrentActivities, double maxTaskQueueActivitiesPerSecond,
                           Duration stickyQueueDrainTimeout, Duration gracefulShutdownTimeout) {
        public Capacity {
            if (workflowCacheSize < 1 || workflowCacheSize > 10_000
                    || maxWorkflowThreads < workflowCacheSize || maxWorkflowThreads > 20_000
                    || workflowTaskPollers < 1 || workflowTaskPollers > 32
                    || activityTaskPollers < 1 || activityTaskPollers > 64
                    || maxConcurrentWorkflowTasks < 1 || maxConcurrentWorkflowTasks > 1_000
                    || maxConcurrentActivities < 1 || maxConcurrentActivities > 1_000
                    || !Double.isFinite(maxTaskQueueActivitiesPerSecond)
                    || maxTaskQueueActivitiesPerSecond <= 0 || maxTaskQueueActivitiesPerSecond > 10_000
                    || stickyQueueDrainTimeout == null || stickyQueueDrainTimeout.isNegative()
                    || stickyQueueDrainTimeout.compareTo(Duration.ofMinutes(5)) > 0
                    || gracefulShutdownTimeout == null || gracefulShutdownTimeout.isZero()
                    || gracefulShutdownTimeout.isNegative()
                    || gracefulShutdownTimeout.compareTo(Duration.ofMinutes(10)) > 0) {
                throw new IllegalArgumentException("Temporal worker capacity is outside operational bounds");
            }
        }

        public static Capacity defaults() {
            return new Capacity(100, 200, 2, 2, 4, 4, 10.0,
                    Duration.ofSeconds(10), Duration.ofSeconds(30));
        }
    }

    public record Security(boolean tlsEnabled, String clientCertificatePath, String privateKeyPath,
                           String serverName, String apiKeyFile) {
        public Security {
            clientCertificatePath = blankToEmpty(clientCertificatePath);
            privateKeyPath = blankToEmpty(privateKeyPath);
            serverName = blankToEmpty(serverName);
            apiKeyFile = blankToEmpty(apiKeyFile);
            boolean certificateConfigured = !clientCertificatePath.isEmpty() || !privateKeyPath.isEmpty();
            if ((tlsEnabled && serverName.isEmpty())
                    || certificateConfigured && (clientCertificatePath.isEmpty() || privateKeyPath.isEmpty())
                    || (!apiKeyFile.isEmpty() || certificateConfigured) && !tlsEnabled
                    || !absoluteWhenConfigured(clientCertificatePath)
                    || !absoluteWhenConfigured(privateKeyPath)
                    || !absoluteWhenConfigured(apiKeyFile)) {
                throw new IllegalArgumentException("Temporal TLS/authentication configuration is inconsistent");
            }
        }

        private static boolean absoluteWhenConfigured(String value) {
            return value.isEmpty() || Path.of(value).isAbsolute();
        }

        private static String blankToEmpty(String value) {
            return value == null ? "" : value.strip();
        }
    }
}
