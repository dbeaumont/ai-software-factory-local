package com.example.aifactory.agentruntime;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.common.VersioningBehavior;
import io.temporal.common.WorkerDeploymentVersion;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerDeploymentOptions;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.worker.WorkerOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class AgentTemporalConfiguration {
    @Bean(destroyMethod = "shutdown")
    @ConditionalOnProperty(name = "ai-factory.agent-runtime.temporal.enabled", havingValue = "true")
    WorkflowServiceStubs agentWorkflowService(AgentTemporalProperties properties, AgentRuntimeProperties runtime) {
        properties.validate(runtime.role());
        return WorkflowServiceStubs.newServiceStubs(WorkflowServiceStubsOptions.newBuilder()
                .setTarget(properties.target()).build());
    }

    @Bean
    @ConditionalOnProperty(name = "ai-factory.agent-runtime.temporal.enabled", havingValue = "true")
    WorkflowClient agentWorkflowClient(WorkflowServiceStubs agentWorkflowService, AgentTemporalProperties properties) {
        return WorkflowClient.newInstance(agentWorkflowService, WorkflowClientOptions.newBuilder()
                .setNamespace(properties.namespace()).build());
    }

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnProperty(name = "ai-factory.agent-runtime.temporal.enabled", havingValue = "true")
    WorkerFactory agentWorkerFactory(WorkflowClient client, AgentConcurrencyProperties concurrency) {
        return WorkerFactory.newInstance(client, WorkerFactoryOptions.newBuilder()
                .setUsingVirtualWorkflowThreads(true)
                .setShutdownCheckInterval(java.time.Duration.ofMillis(250)).build());
    }

    @Bean
    @ConditionalOnProperty(name = "ai-factory.agent-runtime.temporal.enabled", havingValue = "true")
    Worker agentTaskWorker(WorkerFactory factory, AgentTemporalProperties properties, AgentRuntimeProperties runtime,
                           A2aTaskStore taskStore, EvidenceArtifactPublisher artifactPublisher,
                           AgentConcurrencyProperties concurrency) {
        WorkerDeploymentOptions deployment = WorkerDeploymentOptions.newBuilder()
                .setUseVersioning(true)
                .setVersion(new WorkerDeploymentVersion(properties.deploymentName(), properties.buildId()))
                .setDefaultVersioningBehavior(VersioningBehavior.PINNED)
                .build();
        Worker worker = factory.newWorker(properties.taskQueue(runtime.role()), WorkerOptions.newBuilder()
                .setDeploymentOptions(deployment)
                .setMaxConcurrentWorkflowTaskPollers(concurrency.workflowPollers())
                .setMaxConcurrentActivityTaskPollers(concurrency.activityPollers())
                .setMaxConcurrentWorkflowTaskExecutionSize(concurrency.maxWorkflowExecutions())
                .setMaxConcurrentActivityExecutionSize(concurrency.maxActivityExecutions()).build());
        worker.registerWorkflowImplementationTypes(AgentTaskWorkflowV1Impl.class);
        worker.registerActivitiesImplementations(new AgentTaskProjectionActivitiesImpl(taskStore));
        worker.registerActivitiesImplementations(new AgentArtifactActivitiesImpl(artifactPublisher));
        return worker;
    }

    @Bean
    @ConditionalOnProperty(name = "ai-factory.agent-runtime.temporal.enabled", havingValue = "true")
    TemporalAgentTaskWorkflowGateway temporalAgentTaskWorkflowGateway(
            WorkflowClient client, AgentTemporalProperties properties, AgentRuntimeProperties runtime) {
        return new TemporalAgentTaskWorkflowGateway(client, properties, runtime.role());
    }

    @Bean
    @ConditionalOnProperty(name = "ai-factory.agent-runtime.temporal.enabled", havingValue = "true")
    SmartLifecycle agentWorkerLifecycle(WorkerFactory factory, Worker agentTaskWorker,
                                         AgentConcurrencyProperties concurrency) {
        return new SmartLifecycle() {
            private volatile boolean running;
            @Override public void start() { factory.start(); running = true; }
            @Override public void stop() {
                factory.suspendPolling();
                factory.shutdown();
                factory.awaitTermination(concurrency.shutdownGrace().toMillis(),
                        java.util.concurrent.TimeUnit.MILLISECONDS);
                if (!factory.isTerminated()) factory.shutdownNow();
                running = false;
            }
            @Override public boolean isRunning() { return running; }
        };
    }

    @Bean
    @ConditionalOnProperty(name = "ai-factory.agent-runtime.temporal.enabled", havingValue = "true")
    A2aRecoveryCoordinator a2aRecoveryCoordinator(AgentRuntimeProperties runtime, A2aTaskStore store,
                                                   AgentTaskWorkflowStarter starter,
                                                   A2aPushNotificationSender notificationSender) {
        return new A2aRecoveryCoordinator(runtime.role(), store, starter, notificationSender);
    }

    @Bean
    @ConditionalOnProperty(name = "ai-factory.agent-runtime.temporal.enabled", havingValue = "true")
    SmartLifecycle a2aRecoveryLifecycle(A2aRecoveryCoordinator coordinator) {
        return new SmartLifecycle() {
            private volatile boolean running;
            @Override public void start() {
                running = true;
                Thread.startVirtualThread(() -> coordinator.reconcile().whenComplete((report, failure) -> {
                    if (failure != null) running = false;
                }));
            }
            @Override public void stop() { running = false; }
            @Override public boolean isRunning() { return running; }
            @Override public int getPhase() { return 100; }
        };
    }

    @Bean
    @ConditionalOnMissingBean(AgentTaskWorkflowControl.class)
    AgentTaskWorkflowControl localWorkflowControl() { return new LocalAgentTaskWorkflowControl(); }

    @Bean
    @ConditionalOnMissingBean(AgentTaskWorkflowStarter.class)
    AgentTaskWorkflowStarter localWorkflowStarter() {
        return (submission, envelope) -> new AgentTaskWorkflowStarter.Execution(
                TemporalAgentTaskWorkflowGateway.workflowId(submission.role(), submission.taskId()), "local-disabled");
    }
}
