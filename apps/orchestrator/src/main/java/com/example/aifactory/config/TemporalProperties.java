package com.example.aifactory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

@ConfigurationProperties(prefix = "ai-factory.temporal")
public record TemporalProperties(String target, String namespace, Duration namespaceRetention,
                                 Map<String, String> taskQueues, Security security) {
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
        if (!taskQueues.keySet().containsAll(REQUIRED_QUEUES)
                || taskQueues.values().stream().anyMatch(queue -> queue == null
                || !queue.matches("[a-z][a-z0-9-]{2,63}"))) {
            throw new IllegalArgumentException("Temporal task queues are incomplete or invalid");
        }
        security = security == null ? new Security(false, "", "", "", "") : security;
    }

    private static boolean validTarget(String target) {
        if (target == null || !target.matches("[A-Za-z0-9._-]+:[0-9]{1,5}")) return false;
        int port = Integer.parseInt(target.substring(target.lastIndexOf(':') + 1));
        return port >= 1 && port <= 65_535;
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
