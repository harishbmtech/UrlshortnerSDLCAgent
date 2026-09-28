package com.example.shortener.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One redirect. Stores only privacy-preserving data: the client IP is salted+hashed, never stored raw.
 */
@Entity
@Table(name = "click_event")
public class ClickEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String code;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(length = 512)
    private String referrer;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @Column(name = "client_hash", length = 64)
    private String clientHash;

    protected ClickEvent() {
    }

    public ClickEvent(String code, Instant occurredAt, String referrer, String userAgent, String clientHash) {
        this.code = code;
        this.occurredAt = occurredAt;
        this.referrer = truncate(referrer);
        this.userAgent = truncate(userAgent);
        this.clientHash = clientHash;
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() <= 512 ? s : s.substring(0, 512);
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public Instant getOccurredAt() { return occurredAt; }
    public String getReferrer() { return referrer; }
    public String getUserAgent() { return userAgent; }
    public String getClientHash() { return clientHash; }
}
