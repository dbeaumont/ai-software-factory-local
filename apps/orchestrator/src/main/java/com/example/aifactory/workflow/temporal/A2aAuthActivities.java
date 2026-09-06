package com.example.aifactory.workflow.temporal;

import com.example.aifactory.a2a.A2aAuthGrant;
import com.example.aifactory.a2a.A2aContracts;
import com.example.aifactory.a2a.A2aExecutionContext;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.workflow.Workflow;

public final class A2aAuthActivities {
    private A2aAuthActivities() {}

    @ActivityInterface
    public interface ResumeAuth {
        @ActivityMethod(name = "A2aResumeAuthRequired")
        A2aContracts.TaskSnapshot resumeAuth(ResumeRequest request);
    }

    public static ResumeAuth newStub() {
        return Workflow.newActivityStub(ResumeAuth.class,
                TemporalActivityPolicies.forKind(TemporalActivityPolicies.Kind.A2A_AUTH));
    }

    public record ResumeRequest(A2aExecutionContext execution, A2aContracts.SendCommand command,
                                A2aAuthGrant grant) {}
}
