package com.example.aifactory.config;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class ObservabilityContentPropertiesTest {
    @Test
    void deniesEverySensitiveContentChannelByDefault() {
        ObservabilityContentProperties policy = ObservabilityContentProperties.disabled();

        assertThat(Arrays.stream(ObservabilityContentProperties.ContentKind.values())
                .anyMatch(policy::enabled)).isFalse();
    }

    @Test
    void requiresAnExplicitOptInForEachIndependentChannel() {
        ObservabilityContentProperties policy = new ObservabilityContentProperties(true, false, false);

        assertThat(policy.enabled(ObservabilityContentProperties.ContentKind.PROMPT)).isTrue();
        assertThat(policy.enabled(ObservabilityContentProperties.ContentKind.RESULT)).isFalse();
        assertThat(policy.enabled(ObservabilityContentProperties.ContentKind.EVIDENCE)).isFalse();
    }
}
