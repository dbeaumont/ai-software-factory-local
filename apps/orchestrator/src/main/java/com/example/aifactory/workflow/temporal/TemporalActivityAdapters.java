package com.example.aifactory.workflow.temporal;

import org.springframework.stereotype.Component;

import java.util.Map;

/** Exposes only the activity capabilities authorized for each specialized task queue. */
@Component
public final class TemporalActivityAdapters {
    private final Map<String, Object[]> registrations;

    public TemporalActivityAdapters(PatchIntegrationActivities patchIntegration,
                                    SourceResolutionActivities sourceResolution,
                                    PipelineExecutionActivities pipeline) {
        registrations = Map.of(
                "context", new Object[]{sourceResolution, pipeline},
                "llm", new Object[]{pipeline},
                "sandbox", new Object[]{patchIntegration, pipeline},
                "assurance", new Object[]{pipeline},
                "evidence", new Object[]{pipeline},
                "scm", new Object[]{pipeline});
    }

    public Object[] forWorker(String kind) {
        Object[] activities = registrations.get(kind);
        if (activities == null) throw new IllegalArgumentException("Unknown Temporal activity worker: " + kind);
        return activities.clone();
    }
}
