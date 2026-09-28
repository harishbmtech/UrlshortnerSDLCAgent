package com.example.shortener.service;

import com.example.shortener.support.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenBucketRateLimiterTest {

    @Test
    void allowsBurstUpToCapacityThenThrottlesAndRefills() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(3, 60, clock); // 1 token / second

        assertEquals(0, limiter.tryAcquire("a"));
        assertEquals(0, limiter.tryAcquire("a"));
        assertEquals(0, limiter.tryAcquire("a"));
        long retryAfter = limiter.tryAcquire("a");
        assertTrue(retryAfter >= 1, "should ask the client to back off");

        assertEquals(0, limiter.tryAcquire("b"), "buckets are per client");

        clock.advance(Duration.ofSeconds(1));
        assertEquals(0, limiter.tryAcquire("a"), "one token refilled after one second");
    }

    @Test
    void codeGeneratorProducesBase62CodesOfConfiguredLength() {
        CodeGenerator gen = new CodeGenerator(8);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String code = gen.next();
            assertEquals(8, code.length());
            assertTrue(code.chars().allMatch(c -> CodeGenerator.ALPHABET.indexOf(c) >= 0));
            seen.add(code);
        }
        assertEquals(1000, seen.size(), "random 8-char codes should not collide in a small sample");
    }
}
