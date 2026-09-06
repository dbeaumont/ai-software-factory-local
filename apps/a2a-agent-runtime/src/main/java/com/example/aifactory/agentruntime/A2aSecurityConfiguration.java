package com.example.aifactory.agentruntime;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoders;
import org.springframework.security.web.server.SecurityWebFilterChain;

/** Enforces exactly the mTLS and OAuth2 mechanisms advertised by secure cards. */
@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
@ConditionalOnProperty(name = "ai-factory.agent-runtime.security.enabled", havingValue = "true")
class A2aSecurityConfiguration {

    A2aSecurityConfiguration(A2aSecurityProperties properties, AgentRuntimeProperties runtime,
                             Environment environment) {
        requireSecureTransport(properties, runtime, environment);
    }

    @Bean
    ReactiveJwtDecoder a2aJwtDecoder(A2aSecurityProperties properties) {
        return ReactiveJwtDecoders.fromIssuerLocation(properties.oauth2Issuer().toString());
    }

    @Bean
    SecurityWebFilterChain a2aSecurityWebFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchange -> exchange
                        .pathMatchers(AgentCardController.WELL_KNOWN_PATH, "/actuator/health/**").permitAll()
                        .anyExchange().authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer.jwt(Customizer.withDefaults()))
                .build();
    }

    static void requireSecureTransport(A2aSecurityProperties security, AgentRuntimeProperties runtime,
                                       Environment environment) {
        if (!security.mtlsRequired()) {
            throw new IllegalStateException("Secure A2A runtime requires mTLS");
        }
        if (!Boolean.parseBoolean(environment.getProperty("server.ssl.enabled", "false"))
                || !"need".equalsIgnoreCase(environment.getProperty("server.ssl.client-auth", ""))) {
            throw new IllegalStateException("Secure A2A runtime requires server.ssl.enabled and client-auth=need");
        }
        String protocols = environment.getProperty("server.ssl.enabled-protocols", "");
        if (protocols.isBlank() || !java.util.Arrays.stream(protocols.split(","))
                .map(String::trim).allMatch("TLSv1.3"::equals)) {
            throw new IllegalStateException("Secure A2A runtime permits TLSv1.3 only");
        }
        requireTlsPath(environment, "server.ssl.certificate");
        requireTlsPath(environment, "server.ssl.certificate-private-key");
        requireTlsPath(environment, "server.ssl.trust-certificate");
        requireTlsPath(environment, "ai-factory.agent-runtime.security.crl");
        if (runtime.endpoint() == null || !"https".equalsIgnoreCase(runtime.endpoint().getScheme())) {
            throw new IllegalStateException("Secure A2A endpoint must use HTTPS");
        }
        if (security.oauth2Issuer() == null || security.oauth2TokenUrl() == null
                || security.audience() == null || security.audience().isBlank()) {
            throw new IllegalStateException("Secure A2A runtime requires OAuth2 issuer, token URL and audience");
        }
    }

    private static void requireTlsPath(Environment environment, String property) {
        String value = environment.getProperty(property, "");
        if (value.isBlank()) throw new IllegalStateException("Secure A2A runtime requires " + property);
    }
}

@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
@ConditionalOnProperty(name = "ai-factory.agent-runtime.security.enabled", havingValue = "false", matchIfMissing = true)
class A2aUnsecuredLocalConfiguration {
    @Bean
    SecurityWebFilterChain localSecurityWebFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchange -> exchange.anyExchange().permitAll())
                .build();
    }
}
