package com.example.aifactory.workflow.temporal;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

/** Exposes only the activity capabilities authorized for each specialized task queue. */
@Component
public final class TemporalActivityAdapters {
    private final Map<String, Object[]> registrations;

    @Autowired
    public TemporalActivityAdapters(PatchIntegrationActivities patchIntegration,
                                    SourceResolutionActivities sourceResolution,
                                    PipelineExecutionActivities pipeline,
                                    A2aActivitiesImpl a2a,
                                    HierarchicalRoutingActivities routing,
                                    HierarchicalExecutionActivities hierarchical) {
        java.util.LinkedHashMap<String, Object[]> configured = new java.util.LinkedHashMap<>();
        configured.put("context", new Object[]{sourceResolution, pipeline, routing});
        configured.put("llm", new Object[]{pipeline});
        configured.put("sandbox", new Object[]{patchIntegration, pipeline});
        configured.put("assurance", new Object[]{pipeline});
        configured.put("evidence", new Object[]{pipeline, hierarchical});
        configured.put("scm", new Object[]{pipeline});
        configured.put("workflow", new Object[]{a2a});
        registrations = Map.copyOf(configured);
    }

    public Object[] forWorker(String kind) {
        Object[] activities = registrations.get(kind);
        if (activities == null) throw new IllegalArgumentException("Unknown Temporal activity worker: " + kind);
        return activities.clone();
    }
}
