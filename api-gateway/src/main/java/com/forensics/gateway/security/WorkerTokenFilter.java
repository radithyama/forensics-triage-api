package com.forensics.gateway.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import com.forensics.gateway.TriageProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Guards worker-only endpoints (/internal/**) with a shared secret.
 * Uses a constant-time comparison so the token cannot be recovered via timing.
 */
@Component
public class WorkerTokenFilter implements WebFilter {

    public static final String HEADER = "X-Worker-Token";

    private final byte[] expected;

    public WorkerTokenFilter(TriageProperties props) {
        this.expected = props.workerToken().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!exchange.getRequest().getPath().value().startsWith("/internal/")) {
            return chain.filter(exchange);
        }
        String supplied = exchange.getRequest().getHeaders().getFirst(HEADER);
        if (supplied != null && MessageDigest.isEqual(expected, supplied.getBytes(StandardCharsets.UTF_8))) {
            return chain.filter(exchange);
        }
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        return exchange.getResponse().setComplete();
    }
}
