package com.example.shortener.persistence;

import com.example.shortener.domain.ClickEvent;
import com.example.shortener.domain.LinkStats;
import com.example.shortener.domain.ShortUrl;
import com.example.shortener.domain.ShortenerExceptions.DuplicateCodeException;
import com.example.shortener.service.ShortUrlStore;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * JPA adapter for {@link ShortUrlStore}.
 */
@Component
public class JpaShortUrlStore implements ShortUrlStore {

    private final ShortUrlJpaRepository links;
    private final ClickEventJpaRepository clicks;

    public JpaShortUrlStore(ShortUrlJpaRepository links, ClickEventJpaRepository clicks) {
        this.links = links;
        this.clicks = clicks;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ShortUrl> findByCode(String code) {
        return links.findByCode(code);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsByCode(String code) {
        return links.existsByCode(code);
    }

    @Override
    @Transactional
    public ShortUrl insert(ShortUrl shortUrl) {
        try {
            return links.saveAndFlush(shortUrl);
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateCodeException(shortUrl.getCode(), e);
        }
    }

    @Override
    @Transactional
    public ShortUrl update(ShortUrl shortUrl) {
        return links.save(shortUrl);
    }

    @Override
    @Transactional
    public void recordClick(ClickEvent event) {
        links.incrementClickCount(event.getCode());
        clicks.save(event);
    }

    @Override
    @Transactional(readOnly = true)
    public LinkStats stats(String code, Instant since, int topReferrers) {
        long total = links.findByCode(code).map(ShortUrl::getClickCount).orElse(0L);
        long recent = clicks.countByCodeAndOccurredAtGreaterThanEqual(code, since);
        List<LinkStats.ReferrerCount> refs = clicks.topReferrers(code, PageRequest.of(0, topReferrers)).stream()
                .map(row -> new LinkStats.ReferrerCount(row[0] == null ? "(direct)" : (String) row[0], ((Number) row[1]).longValue()))
                .toList();
        return new LinkStats(code, total, recent, refs);
    }
}
