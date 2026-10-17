package com.forensics.gateway.security;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** JSON error bodies shaped like Spring's default ones, written directly from filters. */
final class ErrorResponses {

    private ErrorResponses() {
    }

    static Mono<Void> write(ServerWebExchange exchange, HttpStatus status, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"timestamp\":\"%s\",\"path\":\"%s\",\"status\":%d,\"error\":\"%s\",\"message\":\"%s\"}".formatted(
                Instant.now(), exchange.getRequest().getPath().value().replace("\"", ""),
                status.value(), status.getReasonPhrase(), message);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8))));
    }
}
