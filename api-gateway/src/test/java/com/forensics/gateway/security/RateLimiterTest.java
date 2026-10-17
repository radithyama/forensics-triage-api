package com.forensics.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

class RateLimiterTest {

    private final AtomicLong now = new AtomicLong();
    private final RateLimiter limiter = new RateLimiter(10, Duration.ofHours(1), now::get);

    private void advance(Duration d) {
        now.addAndGet(d.toNanos());
    }

    @Test
    void allowsTenPerHourThenRejectsWithRetryAfter() {
        for (int i = 0; i < 10; i++) {
            assertThat(limiter.tryAcquire("1.2.3.4").allowed()).isTrue();
        }
        RateLimiter.Decision denied = limiter.tryAcquire("1.2.3.4");
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterSeconds()).isEqualTo(360); // one token every 6 minutes
    }

    @Test
    void refillsContinuouslyAndIsolatesClients() {
        for (int i = 0; i < 10; i++) {
            limiter.tryAcquire("a");
        }
        assertThat(limiter.tryAcquire("b").allowed()).isTrue();
        advance(Duration.ofMinutes(5));
        assertThat(limiter.tryAcquire("a").allowed()).isFalse();
        advance(Duration.ofMinutes(1));
        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        assertThat(limiter.tryAcquire("a").allowed()).isFalse();
    }

    @Test
    void evictsIdleClientsWhenTrackingTooMany() {
        for (int i = 0; i < RateLimiter.MAX_TRACKED_CLIENTS; i++) {
            limiter.tryAcquire("ip-" + i);
        }
        advance(Duration.ofHours(1));
        limiter.tryAcquire("newcomer");
        assertThat(limiter.trackedClients()).isEqualTo(1);
    }

    @Test
    void clientIpUsesTheEntryAppendedByGoogleNotSpoofedOnes() {
        var spoofed = MockServerHttpRequest.get("/").header("X-Forwarded-For", "6.6.6.6, 7.7.7.7, 203.0.113.9").build();
        assertThat(ClientIp.of(spoofed)).isEqualTo("203.0.113.9");
        assertThat(ClientIp.of(MockServerHttpRequest.get("/").header("X-Forwarded-For", "198.51.100.1").build()))
                .isEqualTo("198.51.100.1");
    }
}
