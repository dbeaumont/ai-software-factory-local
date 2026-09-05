package com.example.aifactory.sandbox.service;

import com.example.aifactory.sandbox.config.ComposeSandboxProperties;
import com.example.aifactory.sandbox.config.SandboxExecutionProperties;
import tools.jackson.databind.ObjectMapper;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

class ComposeSandboxRuntimeContractTest extends SandboxRuntimeContract {
    @Override
    protected SandboxRuntime successfulRuntime() {
        return runtime("{\"exitCode\":7,\"output\":\"bounded output\","
                + "\"outputTruncated\":true,\"timedOut\":false}");
    }

    @Override
    protected SandboxRuntime timedOutRuntime() {
        return runtime("{\"exitCode\":137,\"output\":\"partial output\","
                + "\"outputTruncated\":true,\"timedOut\":true}");
    }

    private static SandboxRuntime runtime(String responseBody) {
        HttpClient client = new FixedHttpClient(responseBody.getBytes(StandardCharsets.UTF_8));
        URI runner = URI.create("http://runner.internal:8088");
        ComposeSandboxProperties compose = new ComposeSandboxProperties(
                runner, runner, runner, runner, "t".repeat(64), "test");
        return new ComposeSandboxRuntime(properties(), compose, new ObjectMapper(), client);
    }

    private static SandboxExecutionProperties properties() {
        return new SandboxExecutionProperties(Path.of("/workspace/tasks"), Path.of("/state"), "workspace",
                "sha256:" + "d".repeat(64), "factory", 2, 32, 2, 500, Duration.ofDays(7),
                Duration.ofSeconds(15), 65_536, 1_048_576, "", "", "http://sonarqube:9000", "");
    }

    private static final class FixedHttpClient extends HttpClient {
        private final byte[] responseBody;

        private FixedHttpClient(byte[] responseBody) {
            this.responseBody = responseBody;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            return (HttpResponse<T>) new FixedResponse(request, responseBody);
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            return CompletableFuture.completedFuture(send(request, responseBodyHandler));
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return CompletableFuture.completedFuture(send(request, responseBodyHandler));
        }

        @Override public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        @Override public Optional<Duration> connectTimeout() { return Optional.of(Duration.ofSeconds(1)); }
        @Override public Redirect followRedirects() { return Redirect.NEVER; }
        @Override public Optional<ProxySelector> proxy() { return Optional.empty(); }
        @Override public SSLContext sslContext() { return defaultSslContext(); }
        @Override public SSLParameters sslParameters() { return new SSLParameters(); }
        @Override public Optional<Authenticator> authenticator() { return Optional.empty(); }
        @Override public Version version() { return Version.HTTP_1_1; }
        @Override public Optional<Executor> executor() { return Optional.empty(); }

        private static SSLContext defaultSslContext() {
            try {
                return SSLContext.getDefault();
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }
    }

    private record FixedResponse(HttpRequest request, byte[] body) implements HttpResponse<byte[]> {
        @Override public int statusCode() { return 200; }
        @Override public Optional<HttpResponse<byte[]>> previousResponse() { return Optional.empty(); }
        @Override public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (name, value) -> true); }
        @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
        @Override public URI uri() { return request.uri(); }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }
}
