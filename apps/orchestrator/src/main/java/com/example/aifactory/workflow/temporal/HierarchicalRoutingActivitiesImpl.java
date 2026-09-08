package com.example.aifactory.workflow.temporal;

import com.example.aifactory.model.TaskRoutingFacts;
import com.example.aifactory.service.RoutingDecision;
import com.example.aifactory.service.WorkflowRoutingService;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public final class HierarchicalRoutingActivitiesImpl implements HierarchicalRoutingActivities {
    private final WorkflowRoutingService routing;

    public HierarchicalRoutingActivitiesImpl(WorkflowRoutingService routing) {
        this.routing = Objects.requireNonNull(routing);
    }

    @Override
    public Decision decide(Request request) {
        if (request == null || request.facts() == null) {
            throw new IllegalArgumentException("Routing activity request is required");
        }
        TaskRoutingFacts facts = request.facts();
        RoutingDecision decision = routing.decide(new WorkflowRoutingService.Input(
                request.taskId(), request.sourceCommit(), facts.qualification(), request.repositoryId(),
                facts.risk(), facts.modules(), facts.domains(), facts.estimatedFiles(),
                facts.independentCodeScopes(), facts.impacts(), facts.materialDecisionOpen(),
                facts.inputsComplete(), facts.contradictory(), facts.budgetAvailable()));
        return new Decision(decision.decisionId(), decision.policyId(), decision.policyVersion(),
                decision.normalizedInputs(), decision.matchedRule(), decision.selectedPath(),
                decision.reasons(), decision.agents(), decision.humanGate());
    }
}
