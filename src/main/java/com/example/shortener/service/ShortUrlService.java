package com.example.shortener.service;

import com.example.shortener.domain.ClickEvent;
import com.example.shortener.domain.LinkStats;
import com.example.shortener.domain.ShortUrl;
import com.example.shortener.domain.ShortenerExceptions.AliasConflictException;
import com.example.shortener.domain.ShortenerExceptions.DuplicateCodeException;
import com.example.shortener.domain.ShortenerExceptions.InvalidRequestException;
import com.example.shortener.domain.ShortenerExceptions.LinkGoneException;
import com.example.shortener.domain.ShortenerExceptions.LinkNotFoundException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Core use cases of the URL shortener: create, resolve (redirect), inspect, analytics, disable.
 * Framework-free on purpose; wired by {@code ShortenerConfiguration}.
 */
public class ShortUrlService {

    private static final Pattern ALIAS = Pattern.compile("^[A-Za-z0-9_-]{4,32}$");

    public record CreateCommand(String url, String alias, Long ttlSeconds) {
    }

    public record ClickContext(String referrer, String userAgent, String clientIp) {
    }

    /**
     * @param defaultTtl          applied when the caller gives no TTL; null = links never expire by default
     * @param maxTtl              upper bound for caller-provided TTL
     * @param maxCollisionRetries bounded retry for random-code collisions
     * @param reservedAliases     words that would shadow API/infra routes
     * @param clientHashSalt      salt for hashing client IPs in analytics (privacy)
     */
    public record Policy(Duration defaultTtl, Duration maxTtl, int maxCollisionRetries,
                         Set<String> reservedAliases, String clientHashSalt) {
    }

    private final ShortUrlStore store;
    private final UrlValidator validator;
    private final CodeGenerator codeGenerator;
    private final Clock clock;
    private final Policy policy;

    public ShortUrlService(ShortUrlStore store, UrlValidator validator, CodeGenerator codeGenerator,
                           Clock clock, Policy policy) {
        this.store = store;
        this.validator = validator;
        this.codeGenerator = codeGenerator;
        this.clock = clock;
        this.policy = policy;
    }

    public ShortUrl create(CreateCommand cmd) {
        String target = validator.validateAndNormalize(cmd.url());
        Instant now = clock.instant();
        Instant expiresAt = resolveExpiry(cmd.ttlSeconds(), now);

        if (cmd.alias() != null && !cmd.alias().isBlank()) {
            String alias = cmd.alias().trim();
            validateAlias(alias);
            if (store.existsByCode(alias)) {
                throw new AliasConflictException(alias);
            }
            try {
                return store.insert(new ShortUrl(alias, target, now, expiresAt));
            } catch (DuplicateCodeException e) {
                throw new AliasConflictException(alias); // lost a race with a concurrent request
            }
        }

        for (int attempt = 1; attempt <= policy.maxCollisionRetries(); attempt++) {
            String code = codeGenerator.next();
            if (store.existsByCode(code)) {
                continue;
            }
            try {
                return store.insert(new ShortUrl(code, target, now, expiresAt));
            } catch (DuplicateCodeException e) {
                // concurrent insert of the same random code; retry with a new one
            }
        }
        throw new IllegalStateException("Could not allocate a unique code after "
                + policy.maxCollisionRetries() + " attempts; consider increasing code length");
    }

    /**
     * Resolves a code for redirect and records the click.
     *
     * @return the target URL
     */
    public String resolve(String code, ClickContext ctx) {
        ShortUrl link = store.findByCode(code).orElseThrow(() -> new LinkNotFoundException(code));
        Instant now = clock.instant();
        if (!link.isResolvable(now)) {
            throw new LinkGoneException(code);
        }
        store.recordClick(new ClickEvent(code, now, ctx.referrer(), ctx.userAgent(), hashClient(ctx.clientIp())));
        return link.getTargetUrl();
    }

    public ShortUrl get(String code) {
        return store.findByCode(code).orElseThrow(() -> new LinkNotFoundException(code));
    }

    public LinkStats stats(String code) {
        get(code); // 404 if unknown
        return store.stats(code, clock.instant().minus(Duration.ofHours(24)), 5);
    }

    public void deactivate(String code) {
        ShortUrl link = get(code);
        link.deactivate();
        store.update(link);
    }

    private Instant resolveExpiry(Long ttlSeconds, Instant now) {
        if (ttlSeconds == null) {
            return policy.defaultTtl() == null ? null : now.plus(policy.defaultTtl());
        }
        if (ttlSeconds <= 0) {
            throw new InvalidRequestException("ttlSeconds must be positive");
        }
        Duration ttl = Duration.ofSeconds(ttlSeconds);
        if (policy.maxTtl() != null && ttl.compareTo(policy.maxTtl()) > 0) {
            throw new InvalidRequestException("ttlSeconds exceeds maximum of " + policy.maxTtl().toSeconds());
        }
        return now.plus(ttl);
    }

    private void validateAlias(String alias) {
        if (!ALIAS.matcher(alias).matches()) {
            throw new InvalidRequestException("alias must be 4-32 characters of [A-Za-z0-9_-]");
        }
        if (policy.reservedAliases().contains(alias.toLowerCase(Locale.ROOT))) {
            throw new InvalidRequestException("alias is reserved");
        }
    }

    private String hashClient(String clientIp) {
        if (clientIp == null || clientIp.isBlank()) {
            return null;
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest((policy.clientHashSalt() + ":" + clientIp).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
