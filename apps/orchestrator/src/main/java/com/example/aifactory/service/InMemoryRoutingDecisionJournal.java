package com.example.aifactory.service;

import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Local append-only adapter; a durable TaskMemory projection can replace it without changing routing. */
@Repository
public final class InMemoryRoutingDecisionJournal implements RoutingDecisionJournal {
    private final ConcurrentMap<String, RoutingDecision> decisions = new ConcurrentHashMap<>();

    @Override
    public void append(RoutingDecision decision) {
        RoutingDecision previous = decisions.putIfAbsent(decision.decisionId(), decision);
        if (previous != null && !previous.equals(decision)) {
            throw new IllegalStateException("Routing decision id already contains different facts");
        }
    }

    @Override
    public List<RoutingDecision> findByTask(String taskId) {
        return decisions.values().stream().filter(decision -> decision.taskId().equals(taskId))
                .sorted(java.util.Comparator.comparing(RoutingDecision::decisionId)).toList();
    }

    @Override
    public List<RoutingDecision> list() {
        return decisions.values().stream().sorted(java.util.Comparator.comparing(RoutingDecision::decisionId))
                .toList();
    }
}
