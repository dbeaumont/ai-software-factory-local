package com.example.aifactory.sandbox.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SandboxDependencyPropertiesTest {

    @Test
    void derivesGradleJvmProxyOptionsFromTheControlledProxyUrl() {
        SandboxDependencyProperties properties = new SandboxDependencyProperties(
                "https://registry.npmjs.org/", "http://sandbox-egress-proxy:3128",
                "artifactory,sonarqube", "ai-factory-sandbox-quality");

        assertEquals("-Dhttp.proxyHost=sandbox-egress-proxy -Dhttp.proxyPort=3128 "
                + "-Dhttps.proxyHost=sandbox-egress-proxy -Dhttps.proxyPort=3128 "
                + "-Dhttp.nonProxyHosts=artifactory|sonarqube",
                properties.gradleProxyOptions());
    }

    @Test
    void rejectsMissingOrCredentialBearingDestinations() {
        assertThrows(IllegalArgumentException.class, () -> new SandboxDependencyProperties(
                "", "http://sandbox-egress-proxy:3128", "artifactory", "quality"));
        assertThrows(IllegalArgumentException.class, () -> new SandboxDependencyProperties(
                "https://registry.npmjs.org/", "http://user:secret@proxy:3128", "artifactory", "quality"));
        assertThrows(IllegalArgumentException.class, () -> new SandboxDependencyProperties(
                "https://registry.npmjs.org/", "http://proxy:3128", "", "quality"));
        assertThrows(IllegalArgumentException.class, () -> new SandboxDependencyProperties(
                "https://registry.npmjs.org/", "http://proxy:8080", "artifactory", "quality"));
    }
}
