package com.forensics.gateway.security;

import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Requires a valid {@code X-API-Key} on every {@code /api/**} request. CORS preflights pass through
 * (CorsWebFilter answers them before this filter runs).
 */
@Component
public class ApiKeyFilter implements WebFilter, Ordered {

    public static final String HEADER = "X-API-Key";
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

    private final ApiKeyProvider keys;
    private final RateLimiter limiter;

    public ApiKeyFilter(ApiKeyProvider keys, RateLimiter limiter) {
        this.keys = keys;
        this.limiter = limiter;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        var request = exchange.getRequest();
        if (!request.getPath().value().startsWith("/api/") || request.getMethod() == HttpMethod.OPTIONS) {
            return chain.filter(exchange);
        }
        if (keys.matches(request.getHeaders().getFirst(HEADER))) {
            return chain.filter(exchange);
        }
        // Wrong guesses spend the caller's rate-limit tokens, capping brute force at 10 attempts/hour/IP.
        RateLimiter.Decision d = limiter.tryAcquire(ClientIp.of(request));
        if (!d.allowed()) {
            return RateLimitFilter.tooManyRequests(exchange, d);
        }
        return ErrorResponses.write(exchange, HttpStatus.UNAUTHORIZED, "Missing or invalid " + HEADER);
    }
}
