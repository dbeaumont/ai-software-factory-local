package com.example.aifactory.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DelegationPolicyPropertiesTest {
    @Test
    void acceptsOnlyValuesWithinTheHostHardCeilings() {
        assertThat(DelegationPolicyProperties.defaults())
                .isEqualTo(new DelegationPolicyProperties(2, 4));

        assertThatThrownBy(() -> new DelegationPolicyProperties(0, 4))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("depth");
        assertThatThrownBy(() -> new DelegationPolicyProperties(3, 4))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("depth");
        assertThatThrownBy(() -> new DelegationPolicyProperties(2, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("fan-out");
        assertThatThrownBy(() -> new DelegationPolicyProperties(2, 5))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("fan-out");
    }
}
