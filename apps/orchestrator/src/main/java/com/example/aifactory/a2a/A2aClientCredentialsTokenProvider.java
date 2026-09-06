package com.example.aifactory.a2a;

import com.example.aifactory.config.A2aOAuth2ClientProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** RFC 6749 client-credentials acquisition with bounded token lifetime and file-backed client secret. */
public final class A2aClientCredentialsTokenProvider implements A2aAccessTokenProvider {
    private final A2aOAuth2ClientProperties properties;
    private final HttpClient http;
    private final ObjectMapper mapper;
    private final Clock clock;

    public A2aClientCredentialsTokenProvider(A2aOAuth2ClientProperties properties, HttpClient http,
                                             ObjectMapper mapper, Clock clock) {
        this.properties = properties;
        this.http = http;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public CompletionStage<char[]> acquire(String agentRole, Set<String> scopes) {
        if (!properties.enabled()) return CompletableFuture.failedStage(
                new SecurityException("A2A OAuth2 client credentials are disabled"));
        validateScopes(agentRole, scopes);
        byte[] secret;
        try {
            secret = Files.readAllBytes(properties.clientSecretFile());
        } catch (Exception failure) {
            return CompletableFuture.failedStage(new SecurityException("A2A client credential is unavailable"));
        }
        byte[] form = form(secret, scopes);
        Arrays.fill(secret, (byte) 0);
        HttpRequest request = HttpRequest.newBuilder(properties.tokenUrl())
                .timeout(properties.requestTimeout())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(form)).build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                .handle((response, failure) -> {
                    Arrays.fill(form, (byte) 0);
                    if (failure != null) throw new SecurityException("A2A token endpoint is unavailable", failure);
                    byte[] body = response.body();
                    try {
                        if (response.statusCode() != 200) {
                            throw new SecurityException("A2A token endpoint rejected client credentials");
                        }
                        JsonNode token = mapper.readTree(body);
                        if (!"Bearer".equalsIgnoreCase(token.path("token_type").asText())) {
                            throw new SecurityException("A2A token endpoint returned an unsupported token type");
                        }
                        long expiresIn = token.path("expires_in").asLong(0);
                        if (expiresIn <= 0 || Duration.ofSeconds(expiresIn).compareTo(
                                properties.maximumTokenLifetime()) > 0) {
                            throw new SecurityException("A2A token endpoint returned an excessive token lifetime");
                        }
                        String value = token.path("access_token").asText();
                        if (value == null || value.length() < 16) {
                            throw new SecurityException("A2A token endpoint returned no access token");
                        }
                        Instant expiry = clock.instant().plusSeconds(expiresIn);
                        if (!expiry.isAfter(clock.instant())) throw new SecurityException("A2A token already expired");
                        return value.toCharArray();
                    } catch (SecurityException failureResponse) {
                        throw failureResponse;
                    } catch (Exception malformed) {
                        throw new SecurityException("A2A token endpoint returned an invalid response", malformed);
                    } finally {
                        if (body != null) Arrays.fill(body, (byte) 0);
                    }
                });
    }

    private byte[] form(byte[] secret, Set<String> scopes) {
        String secretText = new String(secret, StandardCharsets.UTF_8).trim();
        try {
            String value = "grant_type=client_credentials"
                    + "&client_id=" + encode(properties.clientId())
                    + "&client_secret=" + encode(secretText)
                    + "&audience=" + encode(properties.audience())
                    + "&scope=" + encode(scopes.stream().sorted().reduce((left, right) -> left + " " + right)
                            .orElseThrow());
            return value.getBytes(StandardCharsets.UTF_8);
        } finally {
            // The source byte array is cleared by the caller; no credential is retained by this provider.
            secretText = null;
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static void validateScopes(String agentRole, Set<String> scopes) {
        if (agentRole == null || agentRole.isBlank() || scopes == null || scopes.isEmpty()
                || !scopes.contains("a2a.role." + agentRole)
                || scopes.stream().anyMatch(scope -> scope == null || !scope.matches("a2a\\.[a-z0-9.-]+"))) {
            throw new SecurityException("A2A OAuth2 scope request is not bound to the target role");
        }
    }
}
