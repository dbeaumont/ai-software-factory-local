package com.example.aifactory.service;

import com.example.aifactory.model.TaskRequest;
import com.example.aifactory.model.TaskState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryTaskMemoryTest {
    @Test
    void storesAndRetrievesTaskStateThroughThePort() {
        InMemoryTaskMemory memory = new InMemoryTaskMemory();
        TaskState state = new TaskState("task-1", "AF-0001",
                new TaskRequest("https://example.test/repo.git", "main", "change", null));

        memory.save(state);

        assertThat(memory.find("task-1")).containsSame(state);
        assertThat(memory.find("missing")).isEmpty();
        assertThat(memory.list()).containsExactly(state);
    }
}
