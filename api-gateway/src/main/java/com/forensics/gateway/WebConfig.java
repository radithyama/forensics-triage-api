package com.forensics.gateway;

import com.forensics.gateway.security.ApiKeyFilter;
import com.forensics.gateway.security.RateLimitFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Configuration
public class WebConfig {

    /**
     * Strict CORS: only the deployed frontend origins, only the methods and headers the UI uses.
     * Runs before authentication so preflights succeed and 401/429 responses stay readable by the UI;
     * requests from any other Origin are rejected with 403.
     */
    @Bean
    WebFilter corsFilter(TriageProperties props) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(props.allowedOrigins());
        cors.addAllowedMethod("GET");
        cors.addAllowedMethod("POST");
        cors.addAllowedHeader("Content-Type");
        cors.addAllowedHeader(ApiKeyFilter.HEADER);
        cors.addExposedHeader("Retry-After");
        cors.addExposedHeader(RateLimitFilter.LIMIT_HEADER);
        cors.addExposedHeader(RateLimitFilter.REMAINING_HEADER);
        cors.setAllowCredentials(false);
        cors.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        return new OrderedWebFilter(new CorsWebFilter(source), Ordered.HIGHEST_PRECEDENCE);
    }

    private record OrderedWebFilter(WebFilter delegate, int order) implements WebFilter, Ordered {
        @Override
        public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
            return delegate.filter(exchange, chain);
        }

        @Override
        public int getOrder() {
            return order;
        }
    }
}
