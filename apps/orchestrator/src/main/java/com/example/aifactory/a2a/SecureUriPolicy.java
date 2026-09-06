package com.example.aifactory.a2a;

import java.net.InetAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/** Control-plane exact URI allow-list with fail-closed address checks and DNS pinning. */
public final class SecureUriPolicy {
    private static final Set<String> FORBIDDEN_HOSTS = Set.of(
            "localhost", "localhost.localdomain", "metadata", "metadata.google.internal",
            "metadata.google", "instance-data", "instance-data.ec2.internal");
    private final Set<URI> allowed;
    private final Resolver resolver;
    private final Map<String, Set<String>> pins = new ConcurrentHashMap<>();

    public SecureUriPolicy(Set<URI> allowed, Resolver resolver) {
        if (allowed == null || allowed.isEmpty()) throw new IllegalArgumentException("URI allow-list is empty");
        this.allowed = allowed.stream().map(SecureUriPolicy::normalized).collect(Collectors.toUnmodifiableSet());
        this.allowed.forEach(SecureUriPolicy::validateNetworkTargetSyntax);
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    public static SecureUriPolicy system(Set<URI> allowed) {
        return new SecureUriPolicy(allowed, host -> Arrays.asList(InetAddress.getAllByName(host)));
    }

    public URI requireAllowed(URI candidate) {
        URI value = normalized(candidate);
        if (!allowed.contains(value)) throw new SecurityException("Outbound URI is not allow-listed");
        Set<String> current;
        try {
            current = resolver.resolve(value.getHost()).stream().map(address -> {
                if (forbidden(address)) throw new SecurityException("Outbound URI resolves to a forbidden address");
                return address.getHostAddress();
            }).collect(Collectors.toUnmodifiableSet());
        } catch (SecurityException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new SecurityException("Outbound URI DNS resolution failed", failure);
        }
        if (current.isEmpty()) throw new SecurityException("Outbound URI has no approved DNS resolution");
        Set<String> pinned = pins.putIfAbsent(value.getHost().toLowerCase(Locale.ROOT), current);
        if (pinned != null && !pinned.equals(current)) {
            throw new SecurityException("Outbound URI DNS resolution changed after pinning");
        }
        return value;
    }

    public static void validateNetworkTargetSyntax(URI uri) {
        URI value = normalized(uri);
        String host = value.getHost().toLowerCase(Locale.ROOT);
        if (!"https".equalsIgnoreCase(value.getScheme()) || FORBIDDEN_HOSTS.contains(host)
                || host.endsWith(".localhost") || value.getUserInfo() != null) {
            throw new SecurityException("Outbound network target is forbidden");
        }
        try {
            if ((host.matches("[0-9.]+") || host.contains(":")) && forbidden(InetAddress.getByName(host))) {
                throw new SecurityException("Outbound network target is forbidden");
            }
        } catch (SecurityException failure) {
            throw failure;
        } catch (Exception invalid) {
            throw new SecurityException("Outbound network target is invalid", invalid);
        }
    }

    private static boolean forbidden(InetAddress address) {
        return address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isMulticastAddress();
    }

    private static URI normalized(URI uri) {
        if (uri == null || uri.getScheme() == null || uri.getHost() == null || uri.getFragment() != null) {
            throw new SecurityException("Outbound URI is invalid");
        }
        return uri.normalize();
    }

    @FunctionalInterface
    public interface Resolver { java.util.List<InetAddress> resolve(String host) throws Exception; }
}
