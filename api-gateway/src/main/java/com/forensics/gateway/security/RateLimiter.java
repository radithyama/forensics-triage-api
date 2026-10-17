package com.forensics.gateway.security;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * In-memory token bucket per client key: {@code capacity} tokens, refilled continuously over
 * {@code period}. State is per instance, so the gateway is deployed with max-instances=1 to keep
 * the limit global. That costs nothing at idle, and one instance handles 80 concurrent requests.
 */
@Component
public class RateLimiter {

    /** Stops a flood of distinct IPs from growing the map without bound. */
    static final int MAX_TRACKED_CLIENTS = 50_000;

    public record Decision(boolean allowed, int remaining, long retryAfterSeconds) {
    }

    private static final class Bucket {
        double tokens;
        long updatedNanos;

        Bucket(double tokens, long now) {
            this.tokens = tokens;
            this.updatedNanos = now;
        }
    }

    private final int capacity;
    private final double tokensPerNano;
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    @Autowired
    public RateLimiter(@Value("${triage.rate-limit.requests:10}") int capacity,
                       @Value("${triage.rate-limit.period:1h}") Duration period) {
        this(capacity, period, System::nanoTime);
    }

    RateLimiter(int capacity, Duration period, LongSupplier clock) {
        this.capacity = capacity;
        this.tokensPerNano = capacity / (double) period.toNanos();
        this.clock = clock;
    }

    public int capacity() {
        return capacity;
    }

    public Decision tryAcquire(String key) {
        long now = clock.getAsLong();
        if (buckets.size() >= MAX_TRACKED_CLIENTS) {
            evictFull(now);
        }
        Decision[] out = new Decision[1];
        buckets.compute(key, (k, b) -> {
            Bucket bucket = b == null ? new Bucket(capacity, now) : refill(b, now);
            if (bucket.tokens >= 1) {
                bucket.tokens -= 1;
                out[0] = new Decision(true, (int) bucket.tokens, 0);
            } else {
                long wait = (long) Math.ceil((1 - bucket.tokens) / tokensPerNano / 1e9);
                out[0] = new Decision(false, 0, Math.max(1, wait));
            }
            return bucket;
        });
        return out[0];
    }

    private Bucket refill(Bucket b, long now) {
        b.tokens = Math.min(capacity, b.tokens + (now - b.updatedNanos) * tokensPerNano);
        b.updatedNanos = now;
        return b;
    }

    /** A full bucket is indistinguishable from a new one, so dropping it loses nothing. */
    private void evictFull(long now) {
        buckets.entrySet().removeIf(e -> {
            Bucket b = e.getValue();
            return b.tokens + (now - b.updatedNanos) * tokensPerNano >= capacity;
        });
    }

    int trackedClients() {
        return buckets.size();
    }
}
