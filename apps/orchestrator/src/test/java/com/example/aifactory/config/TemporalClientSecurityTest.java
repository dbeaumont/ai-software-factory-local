package com.example.aifactory.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TemporalClientSecurityTest {
    @TempDir Path root;

    @Test
    void leavesLocalTransportPlaintextWhenTlsIsExplicitlyDisabled() {
        var options = TemporalClientSecurity.build(properties(new TemporalProperties.Security(false, "", "", "", "")));

        assertThat(options.getTarget()).isEqualTo("temporal:7233");
        assertThat(options.getEnableHttps()).isFalse();
        assertThat(options.getGrpcMetadataProviders()).isNullOrEmpty();
    }

    @Test
    void attachesTheMetricsScopeToTheTemporalServiceClient() {
        var scope = new com.uber.m3.tally.NoopScope();

        var options = TemporalClientSecurity.build(
                properties(new TemporalProperties.Security(false, "", "", "", "")), scope);

        assertThat(options.getMetricsScope()).isSameAs(scope);
    }

    @Test
    void rejectsSecretsReadableByGroupOrOthers() throws Exception {
        Path key = root.resolve("api-key");
        Files.writeString(key, "temporal-secret-token");
        if (Files.getFileStore(key).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(key, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.GROUP_READ));
            assertThatThrownBy(() -> TemporalClientSecurity.secretFile(key.toString(), "API key"))
                    .isInstanceOf(SecurityException.class).hasMessageContaining("group or others");
        }
    }

    @Test
    void acceptsOwnerOnlyMountedSecretsAndRejectsSymbolicLinks() throws Exception {
        Path key = root.resolve("api-key");
        Files.writeString(key, "temporal-secret-token");
        if (Files.getFileStore(key).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(key, Set.of(PosixFilePermission.OWNER_READ));
        }
        assertThat(TemporalClientSecurity.secretFile(key.toString(), "API key")).isEqualTo(key);

        Path link = root.resolve("api-key-link");
        Files.createSymbolicLink(link, key);
        assertThatThrownBy(() -> TemporalClientSecurity.secretFile(link.toString(), "API key"))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void loadsApiKeyIntoGrpcMetadataWithoutExposingItInOptions() throws Exception {
        Path key = root.resolve("api-key");
        Files.writeString(key, "temporal-secret-token");
        if (Files.getFileStore(key).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(key, Set.of(PosixFilePermission.OWNER_READ));
        }

        var options = TemporalClientSecurity.build(properties(
                new TemporalProperties.Security(true, "", "", "temporal.example.test", key.toString())));

        assertThat(options.getEnableHttps()).isTrue();
        assertThat(options.getGrpcMetadataProviders()).hasSize(1);
        assertThat(options.toString()).doesNotContain("temporal-secret-token");
    }

    private static TemporalProperties properties(TemporalProperties.Security security) {
        return new TemporalProperties("temporal:7233", "ai-factory-local", java.time.Duration.ofDays(7),
                "ai-factory-orchestrator", "0.1.0",
                Map.of("workflow", "ai-factory-workflows", "context", "ai-factory-context",
                        "llm", "ai-factory-llm", "sandbox", "ai-factory-sandbox",
                        "assurance", "ai-factory-assurance", "evidence", "ai-factory-evidence",
                        "scm", "ai-factory-scm"), TemporalProperties.Capacity.defaults(), security);
    }
}
