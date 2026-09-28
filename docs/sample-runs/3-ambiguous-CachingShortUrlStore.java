package com.example.shortener.persistence;

import com.example.shortener.domain.ClickEvent;
import com.example.shortener.domain.LinkStats;
import com.example.shortener.domain.ShortUrl;
import com.example.shortener.service.ShortUrlStore;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Read-through cache for redirect lookups (decorator over {@link ShortUrlStore}).
 * <ul>
 *   <li>bounded (LRU eviction) so link-scanning traffic cannot exhaust memory</li>
 *   <li>short TTL bounds staleness across replicas; local writes invalidate immediately</li>
 *   <li>misses are not cached, so newly created links are visible at once</li>
 * </ul>
 */
public class CachingShortUrlStore implements ShortUrlStore {

    private record CachedLink(ShortUrl link, Instant loadedAt) {
    }

    private final ShortUrlStore delegate;
    private final Duration ttl;
    private final Clock clock;
    private final Map<String, CachedLink> cache;

    public CachingShortUrlStore(ShortUrlStore delegate, Duration ttl, int maxEntries, Clock clock) {
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be positive");
        }
        this.delegate = delegate;
        this.ttl = ttl;
        this.clock = clock;
        this.cache = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CachedLink> eldest) {
                return size() > maxEntries;
            }
        });
    }

    @Override
    public Optional<ShortUrl> findByCode(String code) {
        CachedLink e = cache.get(code);
        Instant now = clock.instant();
        if (e != null && now.isBefore(e.loadedAt().plus(ttl))) {
            return Optional.of(e.link());
        }
        Optional<ShortUrl> loaded = delegate.findByCode(code);
        loaded.ifPresentOrElse(l -> cache.put(code, new CachedLink(l, now)), () -> cache.remove(code));
        return loaded;
    }

    @Override
    public boolean existsByCode(String code) {
        return delegate.existsByCode(code);
    }

    @Override
    public ShortUrl insert(ShortUrl shortUrl) {
        return delegate.insert(shortUrl);
    }

    @Override
    public ShortUrl update(ShortUrl shortUrl) {
        ShortUrl saved = delegate.update(shortUrl);
        cache.remove(shortUrl.getCode());
        return saved;
    }

    @Override
    public void recordClick(ClickEvent event) {
        delegate.recordClick(event);
        CachedLink e = cache.get(event.getCode());
        if (e != null && e.link() != null) {
            synchronized (e.link()) {
                e.link().incrementClicks(); // keep the local view of the counter roughly current
            }
        }
    }

    @Override
    public LinkStats stats(String code, Instant since, int topReferrers) {
        return delegate.stats(code, since, topReferrers);
    }

    int size() {
        return cache.size();
    }
}
