package com.example.aifactory.a2a;

import com.example.aifactory.service.AgentCatalog;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CachingAgentCardResolverTest {

    @Test
    void honorsFreshnessEtagRotationAndShortOutageGraceWithoutCachingInvalidCards() {
        AllowListedAgentRegistry registry = new AllowListedAgentRegistry(
                new ObjectMapper(), new AgentCatalog(), "compose",
                host -> List.of(java.net.InetAddress.getByName("192.0.2.10")));
        URI cardUri = registry.require("developer").cardUri();
        RecordingFetcher fetcher = new RecordingFetcher();
        fetcher.responses.add(ok(cardUri, "v1"));
        fetcher.responses.add(new CachingAgentCardResolver.FetchResponse(
                304, cardUri, 0, "v1", Duration.ofHours(1), null));
        MutableClock clock = new MutableClock(Instant.parse("2026-09-06T12:00:00Z"));
        A2aContracts.AgentCardDescriptor descriptor = descriptor(cardUri);
        CachingAgentCardResolver resolver = resolver(registry, fetcher, clock, (body, policy) -> descriptor);

        assertThat(resolver.resolve("developer").toCompletableFuture().join()).isSameAs(descriptor);
        assertThat(resolver.resolve("developer").toCompletableFuture().join()).isSameAs(descriptor);
        assertThat(fetcher.calls).isEqualTo(1);

        clock.advance(Duration.ofMinutes(6));
        assertThat(resolver.resolve("developer").toCompletableFuture().join()).isSameAs(descriptor);
        assertThat(fetcher.lastEtag).isEqualTo("v1");

        resolver.invalidateAfterKeyRotation();
        fetcher.responses.add(ok(cardUri, "v2"));
        resolver.resolve("developer").toCompletableFuture().join();
        assertThat(fetcher.lastEtag).isNull();

        clock.advance(Duration.ofMinutes(6));
        fetcher.failures.add(new IllegalStateException("offline"));
        assertThat(resolver.resolve("developer").toCompletableFuture().join()).isSameAs(descriptor);
        clock.advance(Duration.ofMinutes(3));
        fetcher.failures.add(new IllegalStateException("still offline"));
        assertThatThrownBy(() -> resolver.resolve("developer").toCompletableFuture().join())
                .isInstanceOf(java.util.concurrent.CompletionException.class)
                .hasCauseInstanceOf(CachingAgentCardResolver.CardResolutionException.class);
    }

    @Test
    void neverFallsBackWhenAReachableCardFailsVerification() {
        AllowListedAgentRegistry registry = new AllowListedAgentRegistry(
                new ObjectMapper(), new AgentCatalog(), "compose",
                host -> List.of(java.net.InetAddress.getByName("192.0.2.10")));
        URI cardUri = registry.require("developer").cardUri();
        RecordingFetcher fetcher = new RecordingFetcher();
        fetcher.responses.add(ok(cardUri, "v1"));
        MutableClock clock = new MutableClock(Instant.parse("2026-09-06T12:00:00Z"));
        CachingAgentCardResolver resolver = resolver(registry, fetcher, clock,
                (body, policy) -> { throw new A2aAgentCardVerifier.CardVerificationException("tampered"); });

        assertThatThrownBy(() -> resolver.resolve("developer").toCompletableFuture().join())
                .hasRootCauseInstanceOf(A2aAgentCardVerifier.CardVerificationException.class);
    }

    @Test
    void journalsCardChangesAndKeyInvalidationWithoutRawIdentifiers() {
        AllowListedAgentRegistry registry = new AllowListedAgentRegistry(
                new ObjectMapper(), new AgentCatalog(), "compose",
                host -> List.of(java.net.InetAddress.getByName("192.0.2.10")));
        URI cardUri = registry.require("developer").cardUri();
        RecordingFetcher fetcher = new RecordingFetcher();
        fetcher.responses.add(ok(cardUri, "sensitive-etag-v1"));
        fetcher.responses.add(ok(cardUri, "sensitive-etag-v2"));
        MutableClock clock = new MutableClock(Instant.parse("2026-09-06T12:00:00Z"));
        java.util.List<String> auditLines = new java.util.ArrayList<>();
        CachingAgentCardResolver resolver = new CachingAgentCardResolver(registry, fetcher,
                (role, entry, now) -> new A2aAgentCardVerifier.VerificationPolicy(
                        role, entry.cardUri(), entry.endpoint(), URI.create("https://ai-factory.local"),
                        "ai-factory", Set.of("developer.code-task-v1"), Map.of("kid", "fingerprint"), now),
                (body, policy) -> descriptor(cardUri), new CachingAgentCardResolver.CachePolicy(
                        Duration.ofMinutes(5), Duration.ofMinutes(2)), clock,
                new A2aDecisionJournal(clock, auditLines::add));

        resolver.resolve("developer").toCompletableFuture().join();
        clock.advance(Duration.ofMinutes(6));
        resolver.resolve("developer").toCompletableFuture().join();
        resolver.invalidateAfterKeyRotation();

        assertThat(auditLines).anyMatch(line -> line.contains("type=CARD_CHANGE outcome=CHANGED"));
        assertThat(auditLines).anyMatch(line -> line.contains("type=CARD_CHANGE outcome=INVALIDATED"));
        assertThat(auditLines).allMatch(line -> !line.contains("developer") && !line.contains("sensitive-etag"));
    }

    private static CachingAgentCardResolver resolver(
            AllowListedAgentRegistry registry, RecordingFetcher fetcher, Clock clock,
            CachingAgentCardResolver.CardValidator validator) {
        return new CachingAgentCardResolver(registry, fetcher,
                (role, entry, now) -> new A2aAgentCardVerifier.VerificationPolicy(
                        role, entry.cardUri(), entry.endpoint(), URI.create("https://ai-factory.local"),
                        "ai-factory", Set.of("developer.code-task-v1"), Map.of("kid", "fingerprint"), now),
                validator, new CachingAgentCardResolver.CachePolicy(
                        Duration.ofMinutes(5), Duration.ofMinutes(2)), clock);
    }

    private static CachingAgentCardResolver.FetchResponse ok(URI uri, String etag) {
        return new CachingAgentCardResolver.FetchResponse(
                200, uri, 0, etag, Duration.ofHours(1), "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static A2aContracts.AgentCardDescriptor descriptor(URI cardUri) {
        return new A2aContracts.AgentCardDescriptor("developer", cardUri,
                URI.create("https://a2a-developer:8090/a2a"), "JSONRPC", "1.0", "a".repeat(64),
                List.of("developer.code-task-v1"), false, false);
    }

    private static final class RecordingFetcher implements CachingAgentCardResolver.AgentCardFetcher {
        private final ArrayDeque<CachingAgentCardResolver.FetchResponse> responses = new ArrayDeque<>();
        private final ArrayDeque<Throwable> failures = new ArrayDeque<>();
        private int calls;
        private String lastEtag;

        @Override
        public java.util.concurrent.CompletionStage<CachingAgentCardResolver.FetchResponse> fetch(
                URI allowListedUri, String ifNoneMatch) {
            calls++;
            lastEtag = ifNoneMatch;
            if (!failures.isEmpty()) return CompletableFuture.failedFuture(failures.removeFirst());
            return CompletableFuture.completedFuture(responses.removeFirst());
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;
        MutableClock(Instant instant) { this.instant = instant; }
        void advance(Duration duration) { instant = instant.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
