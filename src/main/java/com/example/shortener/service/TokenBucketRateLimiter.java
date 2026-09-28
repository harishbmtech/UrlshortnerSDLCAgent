package com.example.shortener.service;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-process token-bucket rate limiter keyed by client.
 * <p>
 * Trade-off: per-instance state. With N replicas the effective limit is N x capacity. For a
 * horizontally scaled deployment swap this for a Redis-backed bucket (same interface).
 */
public class TokenBucketRateLimiter {

    private final long capacity;
    private final double refillPerMilli;
    private final Clock clock;
    private final int maxTrackedClients;
    private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public TokenBucketRateLimiter(long capacity, long refillTokensPerMinute, Clock clock) {
        this(capacity, refillTokensPerMinute, clock, 100_000);
    }

    public TokenBucketRateLimiter(long capacity, long refillTokensPerMinute, Clock clock, int maxTrackedClients) {
        if (capacity <= 0 || refillTokensPerMinute <= 0) {
            throw new IllegalArgumentException("capacity and refill rate must be positive");
        }
        this.capacity = capacity;
        this.refillPerMilli = refillTokensPerMinute / 60_000.0;
        this.clock = clock;
        this.maxTrackedClients = maxTrackedClients;
    }

    /**
     * @return 0 if the request is allowed, otherwise the number of seconds the client should wait
     */
    public long tryAcquire(String clientKey) {
        if (buckets.size() >= maxTrackedClients && !buckets.containsKey(clientKey)) {
            evictFullBuckets();
        }
        Bucket bucket = buckets.computeIfAbsent(clientKey, k -> new Bucket(capacity, clock.millis()));
        synchronized (bucket) {
            long now = clock.millis();
            bucket.refill(now, capacity, refillPerMilli);
            if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0;
                return 0;
            }
            double missing = 1.0 - bucket.tokens;
            return Math.max(1, (long) Math.ceil(missing / refillPerMilli / 1000.0));
        }
    }

    private void evictFullBuckets() {
        long now = clock.millis();
        buckets.entrySet().removeIf(e -> {
            synchronized (e.getValue()) {
                e.getValue().refill(now, capacity, refillPerMilli);
                return e.getValue().tokens >= capacity;
            }
        });
    }

    private static final class Bucket {
        double tokens;
        long lastRefill;

        Bucket(long tokens, long now) {
            this.tokens = tokens;
            this.lastRefill = now;
        }

        void refill(long now, long capacity, double refillPerMilli) {
            long elapsed = Math.max(0, now - lastRefill);
            tokens = Math.min(capacity, tokens + elapsed * refillPerMilli);
            lastRefill = now;
        }
    }
}
