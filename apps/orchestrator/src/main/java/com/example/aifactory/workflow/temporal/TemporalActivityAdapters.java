package com.example.aifactory.workflow.temporal;

import com.example.aifactory.workflow.EvidenceRepository;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Exposes only the activity capabilities authorized for each specialized task queue. */
@Component
public final class TemporalActivityAdapters {
    private final Map<String, Object[]> registrations;

    public TemporalActivityAdapters(DurableExecutionActivities durable,
                                    PatchIntegrationActivities patchIntegration,
                                    SourceResolutionActivities sourceResolution) {
        registrations = Map.of(
                "context", new Object[]{new ContextAdapter(durable), sourceResolution},
                "llm", new Object[]{new LlmAdapter(durable)},
                "sandbox", new Object[]{new SandboxAdapter(durable), patchIntegration},
                "assurance", new Object[]{new AssuranceAdapter(durable)},
                "evidence", new Object[]{new EvidenceAdapter(durable)},
                "scm", new Object[]{new ScmAdapter(durable)});
    }

    public Object[] forWorker(String kind) {
        Object[] activities = registrations.get(kind);
        if (activities == null) throw new IllegalArgumentException("Unknown Temporal activity worker: " + kind);
        return activities.clone();
    }

    @ActivityInterface
    public interface ContextActivities {
        @ActivityMethod(name = "ContextInvokeMcpTool")
        DurableExecutionActivities.McpResult invoke(DurableExecutionActivities.McpCall call);
    }

    @ActivityInterface
    public interface LlmActivities {
        @ActivityMethod(name = "LlmInvokeAgent")
        DurableExecutionActivities.AgentResult invoke(DurableExecutionActivities.AgentCall call);
    }

    @ActivityInterface
    public interface SandboxActivities {
        @ActivityMethod(name = "SandboxInvokeMcpTool")
        DurableExecutionActivities.McpResult invoke(DurableExecutionActivities.McpCall call);
    }

    @ActivityInterface
    public interface AssuranceActivities {
        @ActivityMethod(name = "AssuranceInvokeMcpTool")
        DurableExecutionActivities.McpResult invoke(DurableExecutionActivities.McpCall call);
    }

    @ActivityInterface
    public interface EvidenceActivities {
        @ActivityMethod(name = "EvidenceStore")
        EvidenceRepository.StoredEvidence store(DurableExecutionActivities.EvidenceCall call);
    }

    @ActivityInterface
    public interface ScmActivities {
        @ActivityMethod(name = "ScmInvokeMcpTool")
        DurableExecutionActivities.McpResult invoke(DurableExecutionActivities.McpCall call);
    }

    private record ContextAdapter(DurableExecutionActivities delegate) implements ContextActivities {
        @Override public DurableExecutionActivities.McpResult invoke(DurableExecutionActivities.McpCall call) {
            return delegate.invokeMcp(call);
        }
    }

    private record LlmAdapter(DurableExecutionActivities delegate) implements LlmActivities {
        @Override public DurableExecutionActivities.AgentResult invoke(DurableExecutionActivities.AgentCall call) {
            return delegate.invokeAgent(call);
        }
    }

    private record SandboxAdapter(DurableExecutionActivities delegate) implements SandboxActivities {
        @Override public DurableExecutionActivities.McpResult invoke(DurableExecutionActivities.McpCall call) {
            return delegate.invokeMcp(call);
        }
    }

    private record AssuranceAdapter(DurableExecutionActivities delegate) implements AssuranceActivities {
        @Override public DurableExecutionActivities.McpResult invoke(DurableExecutionActivities.McpCall call) {
            return delegate.invokeMcp(call);
        }
    }

    private record EvidenceAdapter(DurableExecutionActivities delegate) implements EvidenceActivities {
        @Override public EvidenceRepository.StoredEvidence store(DurableExecutionActivities.EvidenceCall call) {
            return delegate.storeEvidence(call);
        }
    }

    private record ScmAdapter(DurableExecutionActivities delegate) implements ScmActivities {
        @Override public DurableExecutionActivities.McpResult invoke(DurableExecutionActivities.McpCall call) {
            return delegate.invokeMcp(call);
        }
    }
}
