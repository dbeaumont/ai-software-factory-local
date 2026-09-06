package com.example.aifactory.a2a;

import com.example.aifactory.config.A2aFleetProperties;
import com.example.aifactory.config.A2aNotificationProperties;
import com.example.aifactory.config.A2aOAuth2ClientProperties;
import com.example.aifactory.config.TemporalProperties;
import com.example.aifactory.service.AgentCatalog;
import io.grpc.Deadline;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.temporal.api.enums.v1.TaskQueueType;
import io.temporal.api.taskqueue.v1.TaskQueue;
import io.temporal.api.workflowservice.v1.DescribeNamespaceRequest;
import io.temporal.api.workflowservice.v1.DescribeTaskQueueRequest;
import io.temporal.serviceclient.WorkflowServiceStubs;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Sanitized, explainable admission readiness across every mandatory A2A role and dependency. */
@Component("a2aFleet")
public final class A2aFleetReadinessHealthIndicator implements HealthIndicator {
    private final List<String> roles;
    private final Probe probe;
    private final AtomicInteger ready = new AtomicInteger();
    private final AtomicInteger blockedRoles = new AtomicInteger();
    private final AtomicInteger blockedDependencies = new AtomicInteger();

    @Autowired
    public A2aFleetReadinessHealthIndicator(
            AgentCatalog catalog,
            ObjectProvider<AgentCardResolver> cards,
            ObjectProvider<A2aClient> clients,
            WorkflowServiceStubs temporal,
            TemporalProperties temporalProperties,
            A2aFleetProperties fleet,
            A2aOAuth2ClientProperties oauth2,
            A2aNotificationProperties notifications,
            MeterRegistry meters) {
        this(agentRoles(catalog), new LiveProbe(cards.getIfAvailable(), clients.getIfAvailable(),
                temporal, temporalProperties.namespace(), fleet.readinessTimeout(), oauth2, notifications), meters);
    }

    A2aFleetReadinessHealthIndicator(Set<String> roles, Probe probe, MeterRegistry meters) {
        this.roles = roles.stream().sorted().toList();
        this.probe = probe;
        if (meters != null) {
            Gauge.builder("ai.factory.a2a.fleet.ready", ready, AtomicInteger::get).register(meters);
            Gauge.builder("ai.factory.a2a.fleet.blocked.roles", blockedRoles, AtomicInteger::get).register(meters);
            Gauge.builder("ai.factory.a2a.fleet.blocked.dependencies", blockedDependencies, AtomicInteger::get)
                    .register(meters);
        }
    }

    @Override
    public Health health() {
        Map<String, String> dependencies = probe.dependencies();
        Map<String, RoleStatus> roleStatuses = new LinkedHashMap<>();
        List<Map<String, String>> blockers = new ArrayList<>();
        dependencies.forEach((name, status) -> {
            if (!"READY".equals(status)) blockers.add(blocker("dependency", null, name, status));
        });
        for (String role : roles) {
            RoleStatus status = probe.role(role);
            roleStatuses.put(role, status);
            if (!"READY".equals(status.card())) blockers.add(blocker("card", role, "agent-card", status.card()));
            if (!"READY".equals(status.taskQueue())) {
                blockers.add(blocker("taskQueue", role, "a2a-agent-" + role + "-v1", status.taskQueue()));
            }
        }
        int roleBlockers = (int) blockers.stream().filter(value -> value.containsKey("role"))
                .map(value -> value.get("role")).distinct().count();
        int dependencyBlockers = (int) blockers.stream().filter(value -> "dependency".equals(value.get("kind")))
                .count();
        boolean admissionsOpen = blockers.isEmpty();
        ready.set(admissionsOpen ? 1 : 0);
        blockedRoles.set(roleBlockers);
        blockedDependencies.set(dependencyBlockers);
        Health.Builder health = admissionsOpen ? Health.up() : Health.down();
        return health.withDetail("admissions", admissionsOpen ? "OPEN" : "SUSPENDED")
                .withDetail("requiredRoles", roles)
                .withDetail("dependencies", dependencies)
                .withDetail("roles", roleStatuses)
                .withDetail("blockers", List.copyOf(blockers)).build();
    }

    private static Set<String> agentRoles(AgentCatalog catalog) {
        return catalog.roles().values().stream()
                .filter(role -> Set.of("agent", "sub-agent").contains(role.kind()))
                .map(AgentCatalog.Role::name).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static Map<String, String> blocker(
            String kind, String role, String resource, String status) {
        Map<String, String> value = new LinkedHashMap<>();
        value.put("kind", kind);
        if (role != null) value.put("role", role);
        value.put("resource", resource);
        value.put("status", status);
        return Map.copyOf(value);
    }

    interface Probe {
        Map<String, String> dependencies();
        RoleStatus role(String role);
    }

    record RoleStatus(String card, String taskQueue, int workflowPollers, int activityPollers) {}

    private static final class LiveProbe implements Probe {
        private static final Path TLS_CERTIFICATE = Path.of("/var/run/ai-factory/a2a/tls.crt");
        private static final Path TLS_PRIVATE_KEY = Path.of("/var/run/ai-factory/a2a/tls.key");
        private static final Path TLS_CA = Path.of("/var/run/ai-factory/a2a/ca.crt");
        private final AgentCardResolver cards;
        private final A2aClient client;
        private final WorkflowServiceStubs temporal;
        private final String namespace;
        private final Duration timeout;
        private final A2aOAuth2ClientProperties oauth2;
        private final A2aNotificationProperties notifications;

        private LiveProbe(AgentCardResolver cards, A2aClient client, WorkflowServiceStubs temporal, String namespace,
                          Duration timeout, A2aOAuth2ClientProperties oauth2,
                          A2aNotificationProperties notifications) {
            this.cards = cards;
            this.client = client;
            this.temporal = temporal;
            this.namespace = namespace;
            this.timeout = timeout;
            this.oauth2 = oauth2;
            this.notifications = notifications;
        }

        @Override
        public Map<String, String> dependencies() {
            Map<String, String> result = new LinkedHashMap<>();
            result.put("a2aClient", client == null ? "MISSING" : "READY");
            result.put("agentCardResolver", cards == null ? "MISSING" : "READY");
            result.put("oauth2", oauth2.enabled() && readable(oauth2.clientSecretFile()) ? "READY" : "INVALID");
            result.put("pushNotifications", notifications.enabled()
                    && readable(Path.of(notifications.hmacSecretFile())) ? "READY" : "INVALID");
            result.put("mTLS", readable(TLS_CERTIFICATE) && readable(TLS_PRIVATE_KEY) && readable(TLS_CA)
                    ? "READY" : "INVALID");
            result.put("temporalNamespace", temporalReady() ? "READY" : "UNAVAILABLE");
            return Map.copyOf(result);
        }

        @Override
        public RoleStatus role(String role) {
            String card = cardReady(role) ? "READY" : cards == null ? "MISSING" : "INVALID";
            int workflow = pollers(role, TaskQueueType.TASK_QUEUE_TYPE_WORKFLOW);
            int activity = pollers(role, TaskQueueType.TASK_QUEUE_TYPE_ACTIVITY);
            return new RoleStatus(card, workflow > 0 && activity > 0 ? "READY" : "NO_POLLER", workflow, activity);
        }

        private boolean cardReady(String role) {
            if (cards == null) return false;
            try {
                A2aContracts.AgentCardDescriptor card = cards.resolve(role).toCompletableFuture()
                        .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                return role.equals(card.agentRole()) && !card.skillIds().isEmpty();
            } catch (Exception unavailable) {
                return false;
            }
        }

        private int pollers(String role, TaskQueueType type) {
            try {
                var response = temporal.blockingStub()
                        .withDeadline(Deadline.after(timeout.toMillis(), TimeUnit.MILLISECONDS))
                        .describeTaskQueue(DescribeTaskQueueRequest.newBuilder().setNamespace(namespace)
                                .setTaskQueue(TaskQueue.newBuilder().setName("a2a-agent-" + role + "-v1"))
                                .setTaskQueueType(type).setReportPollers(true).build());
                return response.getPollersCount();
            } catch (RuntimeException unavailable) {
                return 0;
            }
        }

        private boolean temporalReady() {
            try {
                temporal.blockingStub().withDeadline(Deadline.after(timeout.toMillis(), TimeUnit.MILLISECONDS))
                        .describeNamespace(DescribeNamespaceRequest.newBuilder().setNamespace(namespace).build());
                return true;
            } catch (RuntimeException unavailable) {
                return false;
            }
        }

        private static boolean readable(Path path) {
            return path != null && Files.isRegularFile(path) && Files.isReadable(path);
        }
    }
}
