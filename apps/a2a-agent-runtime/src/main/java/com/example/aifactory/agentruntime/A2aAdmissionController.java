package com.example.aifactory.agentruntime;

import java.util.function.Supplier;

/** Serializes admission decisions so durable active-task counts cannot overshoot local runtime limits. */
public final class A2aAdmissionController {
    private final A2aTaskStore store;
    private final AgentConcurrencyProperties limits;

    public A2aAdmissionController(A2aTaskStore store, AgentConcurrencyProperties limits) {
        this.store = store;
        this.limits = limits;
    }

    public synchronized A2aTaskStore.CreateResult admit(
            String messageId, String role, String tenantId, Supplier<A2aTaskStore.CreateResult> create) {
        if (store.findByMessageId(messageId).isPresent()) return create.get();
        if (store.activeCount(role, tenantId) >= limits.maxActiveTasksPerTenant()) {
            throw new A2aOperationalException(A2aOperationalException.Category.QUOTA,
                    "Tenant active-task quota exceeded", null);
        }
        if (store.activeCount(role, null) >= limits.maxQueuedTasks()) {
            throw new A2aOperationalException(A2aOperationalException.Category.QUOTA,
                    "Agent task queue is applying backpressure", null);
        }
        return create.get();
    }
}
