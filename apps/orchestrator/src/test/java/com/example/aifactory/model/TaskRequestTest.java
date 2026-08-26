package com.example.aifactory.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TaskRequestTest {
    @Test
    void defaultsToLocalModeWhenNoModeIsProvided() {
        TaskRequest request = new TaskRequest("repo", "main", "requirement", null);

        assertThat(request.effectiveLlmMode()).isEqualTo(LlmMode.LOCAL);
    }

    @Test
    void keepsTheRequestedCloudMode() {
        TaskRequest request = new TaskRequest("repo", "main", "requirement", LlmMode.CLOUD);

        assertThat(request.effectiveLlmMode()).isEqualTo(LlmMode.CLOUD);
    }
}
