package com.example.shortener.domain;

import java.util.List;

/**
 * Read model for link analytics.
 */
public record LinkStats(String code, long totalClicks, long clicksLast24h, List<ReferrerCount> topReferrers) {

    public record ReferrerCount(String referrer, long clicks) {
    }
}
