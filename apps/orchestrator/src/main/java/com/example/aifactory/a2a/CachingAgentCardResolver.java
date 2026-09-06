package com.example.aifactory.a2a;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded, ETag-aware cache which stores only successfully verified Agent Cards. */
public final class CachingAgentCardResolver implements AgentCardResolver {
    private final AllowListedAgentRegistry registry;
    private final AgentCardFetcher fetcher;
    private final VerificationPolicyProvider policies;
    private final CardValidator validator;
    private final CachePolicy cachePolicy;
    private final Clock clock;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public CachingAgentCardResolver(AllowListedAgentRegistry registry, AgentCardFetcher fetcher,
                                    VerificationPolicyProvider policies, CardValidator validator,
                                    CachePolicy cachePolicy, Clock clock) {
        this.registry = registry;
        this.fetcher = fetcher;
        this.policies = policies;
        this.validator = validator;
        this.cachePolicy = cachePolicy;
        this.clock = clock;
    }

    @Override
    public CompletionStage<A2aContracts.AgentCardDescriptor> resolve(String agentRole) {
        AllowListedAgentRegistry.Entry expected = registry.require(agentRole);
        Instant now = clock.instant();
        CacheEntry current = cache.get(agentRole);
        if (current != null && now.isBefore(current.freshUntil())) {
            return java.util.concurrent.CompletableFuture.completedFuture(current.card());
        }
        String etag = current == null ? null : current.etag();
        return fetcher.fetch(expected.cardUri(), etag).handle((response, failure) -> {
            Instant resolvedAt = clock.instant();
            if (failure != null) {
                return staleDuringOutage(agentRole, current, resolvedAt, failure);
            }
            if (response.status() >= 500) {
                return staleDuringOutage(agentRole, current, resolvedAt,
                        new IllegalStateException("Agent Card endpoint unavailable: " + response.status()));
            }
            registry.validateCardResponse(agentRole, response.finalUri(), response.redirects());
            Duration freshness = min(response.maxAge(), cachePolicy.maximumTtl());
            if (response.status() == 304) {
                if (current == null) throw new CardResolutionException("304 received without a cached card");
                CacheEntry refreshed = new CacheEntry(current.card(), current.etag(),
                        resolvedAt.plus(freshness), resolvedAt.plus(freshness).plus(cachePolicy.staleOnOutage()));
                cache.put(agentRole, refreshed);
                return refreshed.card();
            }
            if (response.status() != 200 || response.body() == null) {
                throw new CardResolutionException("Unexpected Agent Card response: " + response.status());
            }
            A2aContracts.AgentCardDescriptor verified = validator.verify(
                    response.body(), policies.forRole(agentRole, expected, resolvedAt));
            CacheEntry replacement = new CacheEntry(verified, response.etag(),
                    resolvedAt.plus(freshness), resolvedAt.plus(freshness).plus(cachePolicy.staleOnOutage()));
            cache.put(agentRole, replacement);
            return verified;
        });
    }

    /** Forces signature and trust revalidation after a signing-key or trust-anchor rotation. */
    public void invalidateAfterKeyRotation() {
        cache.clear();
    }

    private A2aContracts.AgentCardDescriptor staleDuringOutage(
            String role, CacheEntry current, Instant now, Throwable failure) {
        if (current != null && now.isBefore(current.staleUntil())) {
            return current.card();
        }
        throw new CardResolutionException("No usable Agent Card for " + role, failure);
    }

    private static Duration min(Duration candidate, Duration maximum) {
        if (candidate == null || candidate.isNegative() || candidate.isZero()) return Duration.ZERO;
        return candidate.compareTo(maximum) < 0 ? candidate : maximum;
    }

    public record CachePolicy(Duration maximumTtl, Duration staleOnOutage) {
        public CachePolicy {
            Objects.requireNonNull(maximumTtl, "maximumTtl");
            Objects.requireNonNull(staleOnOutage, "staleOnOutage");
            if (maximumTtl.isNegative() || maximumTtl.isZero() || staleOnOutage.isNegative()) {
                throw new IllegalArgumentException("Agent Card cache durations are invalid");
            }
        }
    }

    public interface AgentCardFetcher {
        CompletionStage<FetchResponse> fetch(URI allowListedUri, String ifNoneMatch);
    }

    public interface VerificationPolicyProvider {
        A2aAgentCardVerifier.VerificationPolicy forRole(
                String role, AllowListedAgentRegistry.Entry registryEntry, Instant now);
    }

    @FunctionalInterface
    public interface CardValidator {
        A2aContracts.AgentCardDescriptor verify(
                byte[] body, A2aAgentCardVerifier.VerificationPolicy verificationPolicy);
    }

    public record FetchResponse(
            int status,
            URI finalUri,
            int redirects,
            String etag,
            Duration maxAge,
            byte[] body) {
        public FetchResponse {
            if (status < 100 || status > 599 || redirects < 0) {
                throw new IllegalArgumentException("Invalid Agent Card HTTP response");
            }
            Objects.requireNonNull(finalUri, "finalUri");
            body = body == null ? null : body.clone();
        }

        @Override public byte[] body() { return body == null ? null : body.clone(); }
    }

    private record CacheEntry(
            A2aContracts.AgentCardDescriptor card,
            String etag,
            Instant freshUntil,
            Instant staleUntil) {}

    public static final class CardResolutionException extends RuntimeException {
        public CardResolutionException(String message) { super(message); }
        public CardResolutionException(String message, Throwable cause) { super(message, cause); }
    }
}
