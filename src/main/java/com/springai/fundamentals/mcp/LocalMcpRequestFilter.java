package com.springai.fundamentals.mcp;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** Restricts the local public-catalog MCP demo; this is not user authentication. */
@Component
@Profile("mcp-server")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LocalMcpRequestFilter implements WebFilter {

    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "::1", "[::1]");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().pathWithinApplication().value();
        if (!path.equals("/mcp") && !path.startsWith("/mcp/")) {
            return chain.filter(exchange);
        }
        URI request = exchange.getRequest().getURI();
        if (request.getHost() == null || !LOCAL_HOSTS.contains(request.getHost().toLowerCase(Locale.ROOT))) {
            return forbidden(exchange);
        }
        List<String> origins = exchange.getRequest().getHeaders().get(HttpHeaders.ORIGIN);
        if (origins != null) {
            if (origins.size() != 1 || !sameOrigin(origins.getFirst(), request)) {
                return forbidden(exchange);
            }
        }
        return chain.filter(exchange);
    }

    private boolean sameOrigin(String value, URI request) {
        try {
            URI origin = URI.create(value);
            return request.getScheme().equalsIgnoreCase(origin.getScheme())
                    && request.getHost().equalsIgnoreCase(origin.getHost())
                    && port(request) == port(origin)
                    && origin.getRawUserInfo() == null
                    && (origin.getRawPath() == null || origin.getRawPath().isEmpty())
                    && origin.getRawQuery() == null && origin.getRawFragment() == null;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private int port(URI uri) {
        return uri.getPort() >= 0 ? uri.getPort() : ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80);
    }

    private Mono<Void> forbidden(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
        return exchange.getResponse().setComplete();
    }
}
