package com.example.aifactory.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** Emits a content-free access event while the HTTP observation is active. */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
final class CorrelatedHttpAccessLogFilter implements WebFilter {
    private static final Logger LOGGER = LoggerFactory.getLogger(CorrelatedHttpAccessLogFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return chain.filter(exchange).doFinally(signal -> {
            String path = exchange.getRequest().getPath().pathWithinApplication().value();
            if (!path.startsWith("/actuator/health")) {
                HttpStatusCode status = exchange.getResponse().getStatusCode();
                LOGGER.info("HTTP request completed method={} path={} status={} signal={}",
                        exchange.getRequest().getMethod(), path, status == null ? 200 : status.value(), signal);
            }
        });
    }
}
