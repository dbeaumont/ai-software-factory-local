package com.example.aifactory.agentruntime;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.profiles.active=agent-runtime",
        "ai-factory.agent-runtime.role=developer",
        "ai-factory.agent-runtime.endpoint=http://agent-developer:8090/a2a"
})
class AgentCardControllerTest {
    @LocalServerPort int port;

    @Test
    void publishesOnlyTheActiveRoleAtTheStandardWellKnownLocation() {
        WebTestClient client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        client.get().uri(AgentCardController.WELL_KNOWN_PATH)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith("application/json")
                .expectBody()
                .jsonPath("$.protocolVersion").isEqualTo("1.0")
                .jsonPath("$.name").isEqualTo("AI Factory developer")
                .jsonPath("$.url").isEqualTo("http://agent-developer:8090/a2a")
                .jsonPath("$.preferredTransport").isEqualTo("JSONRPC")
                .jsonPath("$.additionalInterfaces[0].transport").isEqualTo("JSONRPC")
                .jsonPath("$.additionalInterfaces[0].url").isEqualTo("http://agent-developer:8090/a2a")
                .jsonPath("$.capabilities.streaming").isEqualTo(false)
                .jsonPath("$.capabilities.pushNotifications").isEqualTo(false)
                .jsonPath("$.skills[0].id").isEqualTo("developer.code-task-v1")
                .jsonPath("$.signatures[0].protected").isNotEmpty()
                .jsonPath("$.signatures[0].signature").isNotEmpty()
                .jsonPath("$.signatures[0].header.jwk.kid").isNotEmpty();

        client.get().uri("/.well-known/agent-card-extended.json")
                .exchange()
                .expectStatus().isNotFound();
    }
}
