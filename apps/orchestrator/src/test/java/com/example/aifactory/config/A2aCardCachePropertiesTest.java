package com.example.aifactory.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class A2aCardCachePropertiesTest {
    @Test
    void defaultsRemainBoundedForCompose() {
        A2aCardCacheProperties properties = new A2aCardCacheProperties(null, null, null);
        assertThat(properties.registryProfile()).isEqualTo("compose");
        assertThat(properties.maximumTtl()).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.staleOnOutage()).isEqualTo(Duration.ofMinutes(2));
    }

    @Test
    void refusesAStaleWindowLongerThanTheMaximumTtl() {
        assertThatThrownBy(() -> new A2aCardCacheProperties(
                "compose", Duration.ofMinutes(1), Duration.ofMinutes(2)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
