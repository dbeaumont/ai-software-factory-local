package com.example.aifactory.config;

import com.example.aifactory.workflow.temporal.TemporalWorkerRegistry;
import com.example.aifactory.workflow.temporal.TemporalActivityAdapters;
import com.example.aifactory.workflow.temporal.TemporalTraceContextPropagator;
import com.example.aifactory.workflow.temporal.TemporalWorkerTracingInterceptor;
import com.uber.m3.tally.RootScopeBuilder;
import com.uber.m3.tally.Scope;
import com.uber.m3.util.Duration;
import io.micrometer.core.instrument.MeterRegistry;
import io.temporal.common.reporter.MicrometerClientStatsReporter;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerFactoryOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Mandatory production Temporal SDK graph. Worker polling starts only through the lifecycle gate. */
@Configuration(proxyBeanMethods = false)
public class TemporalRuntimeConfiguration {
    @Bean(destroyMethod = "close")
    Scope temporalMetricsScope(MeterRegistry registry) {
        return new RootScopeBuilder()
                .reporter(new MicrometerClientStatsReporter(registry))
                .reportEvery(Duration.ofSeconds(10));
    }

    @Bean(destroyMethod = "shutdown")
    WorkflowServiceStubs temporalWorkflowServiceStubs(TemporalProperties properties, Scope temporalMetricsScope) {
        return WorkflowServiceStubs.newServiceStubs(TemporalClientSecurity.build(properties, temporalMetricsScope));
    }

    @Bean
    WorkflowClient temporalWorkflowClient(WorkflowServiceStubs service, TemporalProperties properties,
                                          TemporalTraceContextPropagator traceContext) {
        return WorkflowClient.newInstance(service, WorkflowClientOptions.newBuilder()
                .setNamespace(properties.namespace())
                .setContextPropagators(java.util.List.of(traceContext))
                .build());
    }

    @Bean
    WorkerFactory temporalWorkerFactory(WorkflowClient client, TemporalProperties properties,
                                        TemporalWorkerTracingInterceptor tracingInterceptor) {
        TemporalProperties.Capacity capacity = properties.capacity();
        WorkerFactoryOptions options = WorkerFactoryOptions.newBuilder()
                .setWorkflowCacheSize(capacity.workflowCacheSize())
                .setMaxWorkflowThreadCount(capacity.maxWorkflowThreads())
                .setShutdownCheckInterval(java.time.Duration.ofSeconds(1))
                .setWorkerInterceptors(tracingInterceptor)
                .build();
        return WorkerFactory.newInstance(client, options);
    }

    @Bean
    TemporalWorkerRegistry temporalWorkerRegistry(WorkerFactory factory, TemporalProperties properties,
                                                  TemporalActivityAdapters activities) {
        TemporalWorkerRegistry registry = new TemporalWorkerRegistry(factory, properties.taskQueues(),
                properties.deploymentName(), properties.buildId(), properties.capacity());
        for (String kind : java.util.List.of("workflow", "context", "llm", "sandbox", "assurance", "evidence", "scm")) {
            Object[] implementations = activities.forWorker(kind);
            if (implementations.length > 0) registry.worker(kind).registerActivitiesImplementations(implementations);
        }
        return registry;
    }
}
