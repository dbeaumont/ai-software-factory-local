package com.example.aifactory.a2a;

import com.example.aifactory.service.AgentCatalog;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AllowListedAgentRegistryTest {

    @Test
    void containsExactlyEveryAgentRoleForComposeAndGke() {
        AgentCatalog catalog = new AgentCatalog();
        AllowListedAgentRegistry compose = registry(catalog, "compose");
        AllowListedAgentRegistry gke = registry(catalog, "gke");

        assertThat(compose.entries()).hasSize(14);
        assertThat(gke.entries()).hasSize(14);
        assertThat(compose.require("developer").cardUri().toString())
                .isEqualTo("https://a2a-developer:8090/.well-known/agent-card.json");
        assertThat(gke.require("developer").endpoint().toString())
                .isEqualTo("https://a2a-developer.ai-factory-agents.svc.cluster.local:8090/a2a");
    }

    @Test
    void rejectsUnknownRolesRedirectsAndUnexpectedOrigins() {
        AllowListedAgentRegistry registry = new AllowListedAgentRegistry(
                new ObjectMapper(), new AgentCatalog(), "compose", safeResolver());
        URI expected = registry.require("developer").cardUri();

        assertThat(registry.validateCardResponse("developer", expected, 0)).isEqualTo(expected);
        assertThatThrownBy(() -> registry.require("unknown"))
                .isInstanceOf(AllowListedAgentRegistry.RegistryViolation.class);
        assertThatThrownBy(() -> registry.validateCardResponse("developer", expected, 1))
                .isInstanceOf(AllowListedAgentRegistry.RegistryViolation.class)
                .hasMessageContaining("redirects");
        assertThatThrownBy(() -> registry.validateCardResponse(
                "developer", URI.create("https://attacker.invalid/card.json"), 0))
                .isInstanceOf(AllowListedAgentRegistry.RegistryViolation.class)
                .hasMessageContaining("unexpected origin");
    }

    @Test
    void permitsOnlyPinnedPrivateAddressesForClosedInternalProfiles() throws Exception {
        AtomicReference<String> address = new AtomicReference<>("172.20.0.10");
        AllowListedAgentRegistry registry = new AllowListedAgentRegistry(
                new ObjectMapper(), new AgentCatalog(), "compose",
                host -> List.of(InetAddress.getByName(address.get())));

        assertThat(registry.require("developer").endpoint().getHost()).isEqualTo("a2a-developer");
        address.set("172.20.0.11");
        assertThatThrownBy(() -> registry.require("developer"))
                .isInstanceOf(SecurityException.class).hasMessageContaining("changed after pinning");
    }

    private static AllowListedAgentRegistry registry(AgentCatalog catalog, String profile) {
        return new AllowListedAgentRegistry(new ObjectMapper(), catalog, profile, safeResolver());
    }

    private static SecureUriPolicy.Resolver safeResolver() {
        return host -> java.util.List.of(java.net.InetAddress.getByName("192.0.2.10"));
    }
}
