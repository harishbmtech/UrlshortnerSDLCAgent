package com.example.shortener.service;

import com.example.shortener.domain.ClickEvent;
import com.example.shortener.domain.LinkStats;
import com.example.shortener.domain.ShortUrl;

import java.time.Instant;
import java.util.Optional;

/**
 * Persistence port. The service depends on this interface, not on Spring Data, which keeps the
 * domain logic unit-testable with an in-memory implementation and lets storage evolve
 * (e.g. JPA today, Redis/DynamoDB for the redirect path later) without touching business rules.
 */
public interface ShortUrlStore {

    Optional<ShortUrl> findByCode(String code);

    boolean existsByCode(String code);

    /**
     * @throws com.example.shortener.domain.ShortenerExceptions.DuplicateCodeException if the code is already taken
     */
    ShortUrl insert(ShortUrl shortUrl);

    ShortUrl update(ShortUrl shortUrl);

    /** Atomically increments the counter and appends the click event. */
    void recordClick(ClickEvent event);

    LinkStats stats(String code, Instant since, int topReferrers);
}
