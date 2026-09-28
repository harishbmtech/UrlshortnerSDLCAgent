package com.example.shortener.support;

import com.example.shortener.domain.ClickEvent;
import com.example.shortener.domain.LinkStats;
import com.example.shortener.domain.ShortUrl;
import com.example.shortener.domain.ShortenerExceptions.DuplicateCodeException;
import com.example.shortener.service.ShortUrlStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/** Test double for the persistence port. */
public class InMemoryShortUrlStore implements ShortUrlStore {

    private final Map<String, ShortUrl> byCode = new ConcurrentHashMap<>();
    private final List<ClickEvent> clicks = new CopyOnWriteArrayList<>();
    private final AtomicLong ids = new AtomicLong();

    @Override
    public Optional<ShortUrl> findByCode(String code) {
        return Optional.ofNullable(byCode.get(code));
    }

    @Override
    public boolean existsByCode(String code) {
        return byCode.containsKey(code);
    }

    @Override
    public ShortUrl insert(ShortUrl shortUrl) {
        if (byCode.putIfAbsent(shortUrl.getCode(), shortUrl) != null) {
            throw new DuplicateCodeException(shortUrl.getCode(), null);
        }
        shortUrl.assignId(ids.incrementAndGet());
        return shortUrl;
    }

    @Override
    public ShortUrl update(ShortUrl shortUrl) {
        byCode.put(shortUrl.getCode(), shortUrl);
        return shortUrl;
    }

    @Override
    public synchronized void recordClick(ClickEvent event) {
        byCode.get(event.getCode()).incrementClicks();
        clicks.add(event);
    }

    @Override
    public LinkStats stats(String code, Instant since, int topReferrers) {
        List<ClickEvent> mine = clicks.stream().filter(c -> c.getCode().equals(code)).toList();
        long recent = mine.stream().filter(c -> !c.getOccurredAt().isBefore(since)).count();
        Map<String, Long> byRef = mine.stream().collect(Collectors.groupingBy(
                c -> c.getReferrer() == null ? "(direct)" : c.getReferrer(), Collectors.counting()));
        List<LinkStats.ReferrerCount> top = new ArrayList<>();
        byRef.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
                .limit(topReferrers)
                .forEach(e -> top.add(new LinkStats.ReferrerCount(e.getKey(), e.getValue())));
        return new LinkStats(code, byCode.get(code).getClickCount(), recent, top);
    }

    public List<ClickEvent> clicks() {
        return clicks;
    }
}
