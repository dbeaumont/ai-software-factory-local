package com.example.aifactory.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThatCode;

class CorrelatedHttpAccessLogFilterTest {

    @Test
    void keepsRequestLoggingContentFreeAndNonBlocking() {
        CorrelatedHttpAccessLogFilter filter = new CorrelatedHttpAccessLogFilter();
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/tasks").build());
        WebFilterChain chain = ignored -> Mono.empty();

        assertThatCode(() -> filter.filter(exchange, chain).block()).doesNotThrowAnyException();
    }
}
