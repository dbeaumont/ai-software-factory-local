package com.example.aifactory.workflow.temporal;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Map;

/** Exposes only the activity capabilities authorized for each specialized task queue. */
@Component
public final class TemporalActivityAdapters {
    private final Map<String, Object[]> registrations;

    @Autowired
    public TemporalActivityAdapters(PatchIntegrationActivities patchIntegration,
                                    SourceResolutionActivities sourceResolution,
                                    PipelineExecutionActivities pipeline,
                                    ObjectProvider<A2aActivitiesImpl> a2aProvider) {
        java.util.LinkedHashMap<String, Object[]> configured = new java.util.LinkedHashMap<>();
        configured.put("context", new Object[]{sourceResolution, pipeline});
        configured.put("llm", new Object[]{pipeline});
        configured.put("sandbox", new Object[]{patchIntegration, pipeline});
        configured.put("assurance", new Object[]{pipeline});
        configured.put("evidence", new Object[]{pipeline});
        configured.put("scm", new Object[]{pipeline});
        A2aActivitiesImpl a2a = a2aProvider.getIfAvailable();
        configured.put("workflow", a2a == null ? new Object[]{} : new Object[]{a2a});
        registrations = Map.copyOf(configured);
    }

    TemporalActivityAdapters(PatchIntegrationActivities patchIntegration,
                             SourceResolutionActivities sourceResolution,
                             PipelineExecutionActivities pipeline) {
        this(patchIntegration, sourceResolution, pipeline, new EmptyProvider<>());
    }

    public Object[] forWorker(String kind) {
        Object[] activities = registrations.get(kind);
        if (activities == null) throw new IllegalArgumentException("Unknown Temporal activity worker: " + kind);
        return activities.clone();
    }

    private static final class EmptyProvider<T> implements ObjectProvider<T> {
        @Override public T getObject(Object... args) {
            throw new org.springframework.beans.factory.NoSuchBeanDefinitionException(Object.class);
        }
        @Override public T getIfAvailable() { return null; }
        @Override public T getObject() {
            throw new org.springframework.beans.factory.NoSuchBeanDefinitionException(Object.class);
        }
        @Override public T getIfUnique() { return null; }
        @Override public java.util.Iterator<T> iterator() { return java.util.Collections.emptyIterator(); }
    }
}
