package com.example.shortener.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Set;

/**
 * Externalised configuration for the shortener (see application.yml, prefix {@code shortener}).
 */
@ConfigurationProperties(prefix = "shortener")
public record ShortenerProperties(
        String baseUrl,
        int codeLength,
        Duration defaultTtl,
        Duration maxTtl,
        Set<String> blockedDomains,
        Set<String> reservedAliases,
        String clientHashSalt,
        RateLimit rateLimit) {

    public record RateLimit(long capacity, long refillPerMinute) {
    }

    public ShortenerProperties {
        if (baseUrl == null || baseUrl.isBlank()) baseUrl = "http://localhost:8080";
        if (codeLength == 0) codeLength = 7;
        if (maxTtl == null) maxTtl = Duration.ofDays(365);
        if (blockedDomains == null) blockedDomains = Set.of();
        if (reservedAliases == null) reservedAliases = Set.of("api", "actuator", "health", "admin", "static");
        if (clientHashSalt == null) clientHashSalt = "change-me";
        if (rateLimit == null) rateLimit = new RateLimit(30, 30);
    }
}
