package com.example.aifactory.workflow.temporal;

import com.example.aifactory.model.TaskRoutingFacts;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.util.List;
import java.util.Map;

/** Persists the host-owned routing decision in workflow history after source attestation. */
@ActivityInterface
public interface HierarchicalRoutingActivities {
    @ActivityMethod(name = "DecideHierarchicalRoute")
    Decision decide(Request request);

    record Request(String taskId, String repositoryId, String sourceCommit, TaskRoutingFacts facts) {}

    record Decision(String decisionId, String policyId, String policyVersion,
                    Map<String, String> normalizedInputs, String matchedRule, String selectedPath,
                    List<String> reasons, List<String> agents, String humanGate) {
        public Decision {
            normalizedInputs = Map.copyOf(normalizedInputs);
            reasons = List.copyOf(reasons);
            agents = List.copyOf(agents);
        }
    }
}
