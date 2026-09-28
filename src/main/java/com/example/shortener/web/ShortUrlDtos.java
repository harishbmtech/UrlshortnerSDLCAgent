package com.example.shortener.web;

import com.example.shortener.domain.LinkStats;
import com.example.shortener.domain.ShortUrl;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * API contract (v1). Records are immutable and serialised by Jackson.
 */
public final class ShortUrlDtos {

    private ShortUrlDtos() {
    }

    public record CreateShortUrlRequest(
            @NotBlank @Size(max = 2048) String url,
            @Size(min = 4, max = 32) String alias,
            @Positive Long ttlSeconds) {
    }

    public record ShortUrlResponse(String code, String shortUrl, String targetUrl, Instant createdAt,
                                   Instant expiresAt, boolean active, long clickCount) {

        static ShortUrlResponse from(ShortUrl s, String baseUrl) {
            return new ShortUrlResponse(s.getCode(), baseUrl + "/" + s.getCode(), s.getTargetUrl(),
                    s.getCreatedAt(), s.getExpiresAt(), s.isActive(), s.getClickCount());
        }
    }

    public record StatsResponse(String code, long totalClicks, long clicksLast24h, List<LinkStats.ReferrerCount> topReferrers) {

        static StatsResponse from(LinkStats s) {
            return new StatsResponse(s.code(), s.totalClicks(), s.clicksLast24h(), s.topReferrers());
        }
    }
}
