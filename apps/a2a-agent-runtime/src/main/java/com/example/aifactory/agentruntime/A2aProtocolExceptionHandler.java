package com.example.aifactory.agentruntime;

import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.UnsupportedMediaTypeStatusException;
import reactor.core.publisher.Mono;

/** Keeps transport-level media type rejection distinct from JSON-RPC error envelopes. */
@Order(-2)
@RestControllerAdvice
final class A2aProtocolExceptionHandler {
    @ExceptionHandler(UnsupportedMediaTypeStatusException.class)
    Mono<Void> unsupportedMediaType(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        return exchange.getResponse().setComplete();
    }
}
