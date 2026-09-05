package com.example.aifactory.config;

import com.example.aifactory.workflow.temporal.TemporalWorkerRegistry;
import com.example.aifactory.workflow.temporal.TemporalActivityAdapters;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.WorkerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Mandatory production Temporal SDK graph. Worker polling starts only through the lifecycle gate. */
@Configuration(proxyBeanMethods = false)
public class TemporalRuntimeConfiguration {
    @Bean(destroyMethod = "shutdown")
    WorkflowServiceStubs temporalWorkflowServiceStubs(TemporalProperties properties) {
        return WorkflowServiceStubs.newServiceStubs(TemporalClientSecurity.build(properties));
    }

    @Bean
    WorkflowClient temporalWorkflowClient(WorkflowServiceStubs service, TemporalProperties properties) {
        return WorkflowClient.newInstance(service, WorkflowClientOptions.newBuilder()
                .setNamespace(properties.namespace())
                .build());
    }

    @Bean(destroyMethod = "shutdown")
    WorkerFactory temporalWorkerFactory(WorkflowClient client) {
        return WorkerFactory.newInstance(client);
    }

    @Bean
    TemporalWorkerRegistry temporalWorkerRegistry(WorkerFactory factory, TemporalProperties properties,
                                                  TemporalActivityAdapters activities) {
        TemporalWorkerRegistry registry = new TemporalWorkerRegistry(factory, properties.taskQueues(),
                properties.deploymentName(), properties.buildId());
        for (String kind : java.util.List.of("context", "llm", "sandbox", "assurance", "evidence", "scm")) {
            registry.worker(kind).registerActivitiesImplementations(activities.forWorker(kind));
        }
        return registry;
    }
}
