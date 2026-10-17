package com.forensics.gateway.security;

import java.util.regex.Pattern;

import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Per-IP limit on the endpoints that cost money: issuing upload URLs ({@code POST /api/jobs}) and
 * starting analyses ({@code POST /api/jobs/{id}/start}). Status polling isn't limited (it is cheap,
 * and the UI polls every 1.5 s). Failed API-key attempts draw from the same bucket, via ApiKeyFilter.
 */
@Component
public class RateLimitFilter implements WebFilter, Ordered {

    public static final String LIMIT_HEADER = "X-RateLimit-Limit";
    public static final String REMAINING_HEADER = "X-RateLimit-Remaining";
    private static final Pattern LIMITED = Pattern.compile("^/api/jobs(/[0-9a-fA-F-]{36}/start)?/?$");

    private final RateLimiter limiter;

    public RateLimitFilter(RateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    public int getOrder() {
        return ApiKeyFilter.ORDER + 10;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        var request = exchange.getRequest();
        if (request.getMethod() != HttpMethod.POST || !LIMITED.matcher(request.getPath().value()).matches()) {
            return chain.filter(exchange);
        }
        RateLimiter.Decision d = limiter.tryAcquire(ClientIp.of(request));
        var headers = exchange.getResponse().getHeaders();
        headers.set(LIMIT_HEADER, Integer.toString(limiter.capacity()));
        headers.set(REMAINING_HEADER, Integer.toString(d.remaining()));
        if (d.allowed()) {
            return chain.filter(exchange);
        }
        return tooManyRequests(exchange, d);
    }

    static Mono<Void> tooManyRequests(ServerWebExchange exchange, RateLimiter.Decision d) {
        exchange.getResponse().getHeaders().set("Retry-After", Long.toString(d.retryAfterSeconds()));
        return ErrorResponses.write(exchange, HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded, try again later");
    }
}
