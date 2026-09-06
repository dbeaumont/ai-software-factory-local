package com.example.aifactory.config;

import com.example.aifactory.a2a.A2aAgentCardVerifier;
import com.example.aifactory.a2a.A2aClient;
import com.example.aifactory.a2a.A2aClientCredentialsTokenProvider;
import com.example.aifactory.a2a.A2aClientMetrics;
import com.example.aifactory.a2a.A2aContractMapping;
import com.example.aifactory.a2a.A2aJsonRpcHttpTransport;
import com.example.aifactory.a2a.A2aScopedOAuth2Client;
import com.example.aifactory.a2a.A2aTaskAssociationStore;
import com.example.aifactory.a2a.AgentCardResolver;
import com.example.aifactory.a2a.AllowListedAgentRegistry;
import com.example.aifactory.a2a.CachingAgentCardResolver;
import com.example.aifactory.service.AgentCatalog;
import com.example.aifactory.workflow.temporal.A2aActivitiesImpl;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletionStage;

/** Complete fail-closed production graph used by Temporal A2A activities. */
@Configuration(proxyBeanMethods = false)
public class A2aClientRuntimeConfiguration {
    @Bean
    AllowListedAgentRegistry a2aAgentRegistry(ObjectMapper mapper, AgentCatalog catalog,
                                               A2aCardCacheProperties cards) {
        return new AllowListedAgentRegistry(mapper, catalog, cards.registryProfile());
    }

    @Bean
    HttpClient a2aHttpClient(A2aTransportProperties transport) {
        return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5)).sslContext(sslContext(transport)).build();
    }

    @Bean
    AgentCardResolver a2aAgentCardResolver(AllowListedAgentRegistry registry, HttpClient a2aHttpClient,
                                           ObjectMapper mapper, A2aCardCacheProperties cache,
                                           A2aTransportProperties transport, A2aContractMapping contracts) {
        requireCardIdentity(transport);
        A2aAgentCardVerifier verifier = new A2aAgentCardVerifier(mapper);
        Map<String, String> trusted = trustedFingerprints(transport.cardTrustFile(), mapper);
        CachingAgentCardResolver.AgentCardFetcher fetcher = (uri, etag) -> fetch(a2aHttpClient, uri, etag);
        CachingAgentCardResolver.VerificationPolicyProvider policies = (role, entry, now) ->
                new A2aAgentCardVerifier.VerificationPolicy(role, entry.cardUri(), entry.endpoint(),
                        transport.cardProviderUrl(), transport.cardIssuer(), contracts.inputSkills(role),
                        trusted, now);
        return new CachingAgentCardResolver(registry, fetcher, policies, verifier::verify,
                new CachingAgentCardResolver.CachePolicy(cache.maximumTtl(), cache.staleOnOutage()),
                Clock.systemUTC());
    }

    @Bean
    A2aClient a2aClient(A2aOAuth2ClientProperties oauth2, HttpClient a2aHttpClient,
                        ObjectMapper mapper, AllowListedAgentRegistry registry) {
        if (!oauth2.enabled()) throw new IllegalStateException("Enabled A2A fleet requires OAuth2");
        return new A2aScopedOAuth2Client(
                new A2aClientCredentialsTokenProvider(oauth2, a2aHttpClient, mapper, Clock.systemUTC()),
                new A2aJsonRpcHttpTransport(registry, a2aHttpClient, mapper));
    }

    @Bean
    A2aActivitiesImpl a2aActivities(AgentCardResolver cards, A2aClient client,
                                    A2aContractMapping contracts, A2aTaskAssociationStore associations,
                                    A2aClientMetrics metrics) {
        return new A2aActivitiesImpl(cards, client, contracts, associations, metrics);
    }

    private static CompletionStage<CachingAgentCardResolver.FetchResponse> fetch(
            HttpClient http, java.net.URI uri, String etag) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json").GET();
        if (etag != null && !etag.isBlank()) request.header("If-None-Match", etag);
        return http.sendAsync(request.build(), HttpResponse.BodyHandlers.ofByteArray()).thenApply(response ->
                new CachingAgentCardResolver.FetchResponse(response.statusCode(), response.uri(), 0,
                        response.headers().firstValue("ETag").orElse(null),
                        maxAge(response.headers().firstValue("Cache-Control").orElse("")), response.body()));
    }

    private static Duration maxAge(String cacheControl) {
        for (String directive : cacheControl.split(",")) {
            String value = directive.strip();
            if (value.startsWith("max-age=")) {
                try { return Duration.ofSeconds(Long.parseLong(value.substring("max-age=".length()))); }
                catch (NumberFormatException ignored) { return Duration.ZERO; }
            }
        }
        return Duration.ZERO;
    }

    private static void requireCardIdentity(A2aTransportProperties properties) {
        if (properties.cardIssuer() == null || properties.cardIssuer().isBlank()
                || properties.cardProviderUrl() == null
                || !"https".equalsIgnoreCase(properties.cardProviderUrl().getScheme())) {
            throw new IllegalStateException("Enabled A2A fleet requires pinned card issuer and provider");
        }
    }

    static Map<String, String> trustedFingerprints(String value, ObjectMapper mapper) {
        try {
            Path path = requiredFile(value, "card trust");
            JsonNode root = mapper.readTree(Files.readAllBytes(path));
            if (!"1".equals(root.path("version").asText()) || !root.path("keys").isObject()) {
                throw new SecurityException("A2A card trust file has an unsupported format");
            }
            Map<String, String> result = new LinkedHashMap<>();
            root.path("keys").properties().forEach(entry -> {
                String fingerprint = entry.getValue().asText();
                if (!entry.getKey().matches("[A-Za-z0-9._-]{1,128}")
                        || !fingerprint.matches("[0-9a-f]{64}")
                        || result.putIfAbsent(entry.getKey(), fingerprint) != null) {
                    throw new SecurityException("A2A card trust entry is invalid");
                }
            });
            if (result.isEmpty()) throw new SecurityException("A2A card trust file is empty");
            return Map.copyOf(result);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot load A2A card trust file", failure);
        }
    }

    static SSLContext sslContext(A2aTransportProperties properties) {
        try {
            X509Certificate clientCertificate = certificate(requiredFile(properties.certificate(),
                    "client certificate"));
            X509Certificate authority = certificate(requiredFile(properties.trustCertificate(),
                    "trust certificate"));
            PrivateKey privateKey = privateKey(requiredPrivateFile(properties.privateKey(), "private key"));
            char[] password = new char[0];
            KeyStore keys = KeyStore.getInstance(KeyStore.getDefaultType());
            keys.load(null, null);
            keys.setKeyEntry("a2a-client", privateKey, password,
                    new java.security.cert.Certificate[]{clientCertificate});
            KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagers.init(keys, password);
            KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
            trust.load(null, null);
            trust.setCertificateEntry("a2a-ca", authority);
            TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            trustManagers.init(trust);
            SSLContext context = SSLContext.getInstance("TLSv1.3");
            context.init(keyManagers.getKeyManagers(), trustManagers.getTrustManagers(), null);
            return context;
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot initialize A2A mTLS client", failure);
        }
    }

    private static X509Certificate certificate(Path path) throws Exception {
        try (var input = Files.newInputStream(path)) {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
        }
    }

    private static PrivateKey privateKey(Path path) throws Exception {
        String pem = Files.readString(path, StandardCharsets.US_ASCII)
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
        return KeyFactory.getInstance("RSA").generatePrivate(
                new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
    }

    private static Path requiredFile(String value, String label) throws Exception {
        if (value == null || value.isBlank()) throw new SecurityException("A2A " + label + " path is required");
        Path path = Path.of(value);
        if (!path.isAbsolute() || Files.isSymbolicLink(path) || !Files.isRegularFile(path)
                || !Files.isReadable(path) || Files.size(path) < 1 || Files.size(path) > 1_048_576) {
            throw new SecurityException("A2A " + label + " file is invalid");
        }
        return path;
    }

    private static Path requiredPrivateFile(String value, String label) throws Exception {
        Path path = requiredFile(value, label);
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            var permissions = Files.getPosixFilePermissions(path, java.nio.file.LinkOption.NOFOLLOW_LINKS);
            if (!permissions.contains(java.nio.file.attribute.PosixFilePermission.OWNER_READ)
                    || permissions.stream().anyMatch(permission -> permission !=
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ
                    && permission != java.nio.file.attribute.PosixFilePermission.OWNER_WRITE)) {
                throw new SecurityException("A2A " + label + " permissions are unsafe");
            }
        }
        return path;
    }
}
