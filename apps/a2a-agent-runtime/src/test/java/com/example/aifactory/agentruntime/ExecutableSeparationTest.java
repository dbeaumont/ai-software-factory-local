package com.example.aifactory.agentruntime;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutableSeparationTest {
    @Test
    void agentExecutableCarriesServerSdkAndExecutionWorkerWithoutTemporalOrControlPlane() throws Exception {
        String runtimePom = Files.readString(Path.of("pom.xml"));
        String orchestratorPom = Files.readString(Path.of("../orchestrator/pom.xml"));

        assertTrue(runtimePom.contains("a2a-java-sdk-server-common"));
        assertTrue(runtimePom.contains("a2a-java-sdk-transport-jsonrpc"));
        assertFalse(runtimePom.contains("a2a-java-sdk-client</artifactId>"));
        assertTrue(runtimePom.contains("<exclude>io.temporal:*</exclude>"));
        assertTrue(runtimePom.contains("spring-boot-starter-jdbc"));
        assertTrue(runtimePom.contains("flyway-database-postgresql"));
        assertTrue(orchestratorPom.contains("a2a-java-sdk-client"));
        assertFalse(orchestratorPom.contains("a2a-java-sdk-server-common"));
        assertTrue(Files.exists(Path.of("src/main/java/com/example/aifactory/agentruntime/AgentExecutionWorker.java")));
        assertFalse(Files.exists(Path.of("../orchestrator/src/main/java/com/example/aifactory/agentruntime")));
    }
}
