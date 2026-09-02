package com.example.aifactory.service;

import java.util.List;
import java.util.Map;

/** Append-only routing audit port, queryable by task and aggregatable by selected path. */
public interface RoutingDecisionJournal {
    void append(RoutingDecision decision);

    List<RoutingDecision> findByTask(String taskId);

    List<RoutingDecision> list();

    default Map<String, Long> countBySelectedPath() {
        java.util.TreeMap<String, Long> counts = new java.util.TreeMap<>();
        list().forEach(decision -> counts.merge(decision.selectedPath(), 1L, Long::sum));
        return Map.copyOf(counts);
    }
}
