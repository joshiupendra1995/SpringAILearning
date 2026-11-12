package com.springai.fundamentals.mcp;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalMcpRequestFilterTest {

    private final LocalMcpRequestFilter filter = new LocalMcpRequestFilter();

    @Test
    void acceptsSdkClientsWithoutOriginAndSameOriginBrowserRequests() {
        for (String origin : List.of("", "http://127.0.0.1:8090")) {
            var request = MockServerHttpRequest.post("http://127.0.0.1:8090/mcp");
            if (!origin.isEmpty()) request.header("Origin", origin);
            var exchange = MockServerWebExchange.from(request);
            AtomicBoolean forwarded = new AtomicBoolean();

            filter.filter(exchange, ignored -> {
                forwarded.set(true);
                return Mono.empty();
            }).block();

            assertTrue(forwarded.get(), origin);
        }
    }

    @Test
    void rejectsForeignMalformedAndMismatchedOriginsBeforeTheMcpHandler() {
        for (String origin : List.of("https://example.com", "null", "not-an-origin",
                "http://127.0.0.1:8080", "http://127.0.0.1:8090/private", "http://user@127.0.0.1:8090")) {
            var exchange = MockServerWebExchange.from(MockServerHttpRequest
                    .post("http://127.0.0.1:8090/mcp").header("Origin", origin));
            AtomicBoolean forwarded = new AtomicBoolean();

            filter.filter(exchange, ignored -> {
                forwarded.set(true);
                return Mono.empty();
            }).block();

            assertFalse(forwarded.get(), origin);
            assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
        }
    }

    @Test
    void rejectsNonLoopbackHostsAndMultipleOrigins() {
        for (var request : List.of(
                MockServerHttpRequest.post("http://attacker.example/mcp"),
                MockServerHttpRequest.post("http://127.0.0.1:8090/mcp")
                        .header("Origin", "http://127.0.0.1:8090", "https://example.com"))) {
            var exchange = MockServerWebExchange.from(request);
            AtomicBoolean forwarded = new AtomicBoolean();
            filter.filter(exchange, ignored -> {
                forwarded.set(true);
                return Mono.empty();
            }).block();
            assertFalse(forwarded.get());
            assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
        }
    }

    @Test
    void leavesOtherApplicationPathsAlone() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest
                .get("http://example.com/api/knowledge").header("Origin", "https://elsewhere.example"));
        AtomicBoolean forwarded = new AtomicBoolean();
        filter.filter(exchange, ignored -> {
            forwarded.set(true);
            return Mono.empty();
        }).block();
        assertTrue(forwarded.get());
    }
}
