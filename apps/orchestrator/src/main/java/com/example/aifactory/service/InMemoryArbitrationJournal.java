package com.example.aifactory.service;

import com.example.aifactory.workflow.ArbitrationJournal;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Local append-only adapter; PostgreSQL uses the same port and V008 storage model. */
@Component
public final class InMemoryArbitrationJournal implements ArbitrationJournal {
    private final ConcurrentMap<String, Entry> entries = new ConcurrentHashMap<>();

    @Override
    public void append(Entry entry) {
        Entry existing = entries.putIfAbsent(entry.arbitrationId(), entry);
        if (existing != null && !existing.equals(entry)) {
            throw new IllegalStateException("Arbitration journal entry is immutable: " + entry.arbitrationId());
        }
    }

    @Override
    public Optional<Entry> find(String arbitrationId) {
        return Optional.ofNullable(entries.get(arbitrationId));
    }

    @Override
    public List<Entry> list(String taskId, String attemptId) {
        return entries.values().stream().filter(entry -> entry.taskId().equals(taskId)
                        && entry.attemptId().equals(attemptId))
                .sorted(Comparator.comparing(Entry::decidedAt).thenComparing(Entry::arbitrationId)).toList();
    }
}
