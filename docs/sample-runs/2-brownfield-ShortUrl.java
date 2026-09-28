package com.example.shortener.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Objects;

/**
 * Aggregate root for a shortened link.
 * <p>
 * Click counts are NOT mutated through this entity on the hot redirect path; they are incremented
 * with a single atomic UPDATE in the store to avoid lost updates under concurrency.
 */
@Entity
@Table(name = "short_url")
public class ShortUrl {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String code;

    @Column(name = "target_url", nullable = false, length = 2048)
    private String targetUrl;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "click_count", nullable = false)
    private long clickCount;

    @Column(nullable = false)
    private boolean active = true;

    /** Optional redirect budget; null = unlimited (keeps existing links backward compatible). */
    @Column(name = "max_clicks")
    private Long maxClicks;

    @Version
    private long version;

    protected ShortUrl() {
        // for JPA
    }

    public ShortUrl(String code, String targetUrl, Instant createdAt, Instant expiresAt) {
        this.code = Objects.requireNonNull(code, "code");
        this.targetUrl = Objects.requireNonNull(targetUrl, "targetUrl");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.expiresAt = expiresAt;
    }

    public boolean isExpired(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }

    public boolean isResolvable(Instant now) {
        return active && !isExpired(now) && !isClickBudgetExhausted();
    }

    public boolean isClickBudgetExhausted() {
        return maxClicks != null && clickCount >= maxClicks;
    }

    public void limitClicks(Long maxClicks) {
        if (maxClicks != null && maxClicks <= 0) {
            throw new IllegalArgumentException("maxClicks must be positive");
        }
        this.maxClicks = maxClicks;
    }

    public void deactivate() {
        this.active = false;
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getTargetUrl() { return targetUrl; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public long getClickCount() { return clickCount; }
    public boolean isActive() { return active; }
    public Long getMaxClicks() { return maxClicks; }
    public long getVersion() { return version; }

    /** Used only by in-memory stores (tests); the JPA store increments atomically in SQL. */
    public void incrementClicks() { this.clickCount++; }

    /** Used only by in-memory stores (tests) to emulate DB identity generation. */
    public void assignId(Long id) { this.id = id; }
}
