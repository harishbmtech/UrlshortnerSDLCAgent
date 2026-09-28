package com.example.shortener.service;

import com.example.shortener.domain.LinkStats;
import com.example.shortener.domain.ShortUrl;
import com.example.shortener.domain.ShortenerExceptions.AliasConflictException;
import com.example.shortener.domain.ShortenerExceptions.InvalidRequestException;
import com.example.shortener.domain.ShortenerExceptions.LinkGoneException;
import com.example.shortener.domain.ShortenerExceptions.LinkNotFoundException;
import com.example.shortener.support.InMemoryShortUrlStore;
import com.example.shortener.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShortUrlServiceTest {

    private InMemoryShortUrlStore store;
    private MutableClock clock;
    private ShortUrlService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryShortUrlStore();
        clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        service = newService(new CodeGenerator(7));
    }

    private ShortUrlService newService(CodeGenerator generator) {
        var policy = new ShortUrlService.Policy(null, Duration.ofDays(30), 5, Set.of("api", "admin"), "salt");
        return new ShortUrlService(store, new UrlValidator(Set.of("evil.example"), "sho.rt"), generator, clock, policy);
    }

    private static final ShortUrlService.ClickContext CTX =
            new ShortUrlService.ClickContext("https://news.example", "JUnit", "203.0.113.7");

    @Test
    void createsRandomCodeAndResolvesIt() {
        ShortUrl link = service.create(new ShortUrlService.CreateCommand("https://Example.com/a?b=1", null, null));

        assertEquals(7, link.getCode().length());
        assertEquals("https://example.com/a?b=1", link.getTargetUrl());
        assertNull(link.getExpiresAt());
        assertEquals("https://example.com/a?b=1", service.resolve(link.getCode(), CTX));
    }

    @Test
    void customAliasIsHonouredAndConflictsAreRejected() {
        service.create(new ShortUrlService.CreateCommand("https://example.com", "my-link", null));

        assertThrows(AliasConflictException.class,
                () -> service.create(new ShortUrlService.CreateCommand("https://example.org", "my-link", null)));
    }

    @Test
    void reservedAndMalformedAliasesAreRejected() {
        assertThrows(InvalidRequestException.class,
                () -> service.create(new ShortUrlService.CreateCommand("https://example.com", "admin", null)));
        assertThrows(InvalidRequestException.class,
                () -> service.create(new ShortUrlService.CreateCommand("https://example.com", "a b!", null)));
    }

    @Test
    void expiredLinksReturnGone() {
        ShortUrl link = service.create(new ShortUrlService.CreateCommand("https://example.com", null, 60L));
        assertNotNull(link.getExpiresAt());

        clock.advance(Duration.ofSeconds(61));

        assertThrows(LinkGoneException.class, () -> service.resolve(link.getCode(), CTX));
    }

    @Test
    void ttlAboveMaximumIsRejected() {
        long tooLong = Duration.ofDays(31).toSeconds();
        assertThrows(InvalidRequestException.class,
                () -> service.create(new ShortUrlService.CreateCommand("https://example.com", null, tooLong)));
    }

    @Test
    void deactivatedLinksReturnGone() {
        ShortUrl link = service.create(new ShortUrlService.CreateCommand("https://example.com", null, null));
        service.deactivate(link.getCode());

        assertThrows(LinkGoneException.class, () -> service.resolve(link.getCode(), CTX));
    }

    @Test
    void unknownCodeIsNotFound() {
        assertThrows(LinkNotFoundException.class, () -> service.resolve("nope123", CTX));
    }

    @Test
    void clicksAreCountedAndClientIpIsNeverStoredRaw() {
        ShortUrl link = service.create(new ShortUrlService.CreateCommand("https://example.com", null, null));
        service.resolve(link.getCode(), CTX);
        service.resolve(link.getCode(), new ShortUrlService.ClickContext(null, "JUnit", "198.51.100.1"));

        LinkStats stats = service.stats(link.getCode());
        assertEquals(2, stats.totalClicks());
        assertEquals(2, stats.clicksLast24h());
        assertEquals(2, stats.topReferrers().size());
        store.clicks().forEach(c -> {
            assertNotNull(c.getClientHash());
            assertNotEquals("203.0.113.7", c.getClientHash());
        });
    }

    @Test
    void collisionsAreRetriedWithBoundedAttempts() {
        // deterministic generator: first code collides, second is free
        Deque<String> codes = new ArrayDeque<>(List.of("AAAAAAA", "AAAAAAA", "BBBBBBB"));
        CodeGenerator scripted = new CodeGenerator(RandomGenerator.getDefault(), 7) {
            @Override
            public String next() {
                return codes.pop();
            }
        };
        ShortUrlService svc = newService(scripted);
        svc.create(new ShortUrlService.CreateCommand("https://example.com/1", null, null));

        ShortUrl second = svc.create(new ShortUrlService.CreateCommand("https://example.com/2", null, null));

        assertEquals("BBBBBBB", second.getCode());
    }

    @Test
    void giveUpAfterMaxCollisionRetries() {
        CodeGenerator constant = new CodeGenerator(RandomGenerator.getDefault(), 7) {
            @Override
            public String next() {
                return "SAME000";
            }
        };
        ShortUrlService svc = newService(constant);
        svc.create(new ShortUrlService.CreateCommand("https://example.com/1", null, null));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> svc.create(new ShortUrlService.CreateCommand("https://example.com/2", null, null)));
        assertTrue(e.getMessage().contains("5 attempts"));
    }

    @Test
    void blockedAndSelfReferencingTargetsAreRejected() {
        assertThrows(InvalidRequestException.class,
                () -> service.create(new ShortUrlService.CreateCommand("https://login.evil.example/x", null, null)));
        assertThrows(InvalidRequestException.class,
                () -> service.create(new ShortUrlService.CreateCommand("https://sho.rt/abc", null, null)));
    }
}
