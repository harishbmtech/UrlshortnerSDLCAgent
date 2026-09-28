package com.example.sdlc.agents.offline;

import com.example.sdlc.agents.offline.Feature.Impact;
import com.example.sdlc.agents.offline.Feature.RiskSpec;
import com.example.sdlc.model.Enums.RiskLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Curated domain knowledge used by the deterministic (offline / fallback) agent strategies.
 */
public final class FeatureCatalog {

    private FeatureCatalog() {
    }

    private static Pattern p(String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
    }

    public static final List<Feature> ALL = List.of(
            new Feature("shorten", "Create short links",
                    p("url shortener|shorten|short link|create (a )?(short )?link"),
                    List.of("POST /api/v1/urls with a valid http(s) URL returns 201, a unique 7-character Base62 code and a Location header",
                            "Invalid or unsafe target URLs are rejected with 400 and an RFC 7807 problem body"),
                    List.of(new RiskSpec("security", "Sequential codes would let anyone enumerate every stored link",
                                    RiskLevel.MEDIUM, "Random 7-char Base62 codes from SecureRandom (62^7 keyspace) with bounded collision retry"),
                            new RiskSpec("abuse", "Service used to disguise phishing/malware targets",
                                    RiskLevel.MEDIUM, "Scheme allow-list, credential/private-host rejection, configurable domain deny-list")),
                    List.of("Random codes (non-enumerable) over Base62(id): needs a uniqueness check + retry, but no scraping"),
                    List.of("ShortUrlController (REST)", "ShortUrlService (use cases)", "UrlValidator", "CodeGenerator",
                            "ShortUrlStore port + JPA adapter", "Flyway schema"),
                    List.of("POST /api/v1/urls", "GET /api/v1/urls/{code}", "DELETE /api/v1/urls/{code}"),
                    List.of("ADR: hexagonal core - domain logic behind a ShortUrlStore port so storage can change without touching rules",
                            "ADR: Flyway owns the schema; ddl-auto=none"),
                    new Impact(List.of(), List.of(), List.of(), null, "ShortUrlController")),
            new Feature("redirect", "Redirect",
                    p("url shortener|redirect|resolve"),
                    List.of("GET /{code} responds 302 to the target with Cache-Control: no-store; unknown codes return 404"),
                    List.of(new RiskSpec("availability", "Redirect path depends on the database; a DB outage breaks every link",
                            RiskLevel.MEDIUM, "Health checks + connection pool limits; hot-link cache as a follow-up")),
                    List.of("302 (not 301) so every click is counted and disabling a link is immediate, at the cost of an extra hop for repeat visitors"),
                    List.of("RedirectController"),
                    List.of("GET /{code}"),
                    List.of("ADR: 302 + no-store instead of 301 to keep analytics and revocation accurate"),
                    new Impact(List.of(), List.of(), List.of(), null, "RedirectController")),
            new Feature("analytics", "Click analytics",
                    p("analytic|stats|statistic|click count|track(ing)? clicks|metrics per link"),
                    List.of("Every redirect increments the click counter atomically and GET /api/v1/urls/{code}/stats returns total, last-24h and top referrers without storing raw client IPs"),
                    List.of(new RiskSpec("privacy", "Client IPs are personal data (GDPR)",
                                    RiskLevel.MEDIUM, "Store only a salted SHA-256 prefix of the IP; never log IPs"),
                            new RiskSpec("consistency", "Read-modify-write counter loses updates under concurrent redirects",
                                    RiskLevel.MEDIUM, "Single-statement UPDATE ... SET click_count = click_count + 1")),
                    List.of("Synchronous click recording keeps the design simple but adds a write to the hot path; move to an async queue at high RPS"),
                    List.of("ClickEvent entity", "ClickEventJpaRepository"),
                    List.of("GET /api/v1/urls/{code}/stats"),
                    List.of("ADR: atomic SQL increment for counters; raw events kept for time-window analytics"),
                    new Impact(List.of(), List.of(), List.of(), null, "ClickEvent")),
            new Feature("expiry", "Link expiry",
                    p("expir|ttl|time[- ]to[- ]live"),
                    List.of("Links created with ttlSeconds stop resolving once expired and return 410 Gone; TTLs above the configured maximum are rejected"),
                    List.of(new RiskSpec("correctness", "Clock skew between instances shifts expiry by the skew",
                            RiskLevel.LOW, "NTP-synchronised hosts; expiry evaluated against a single injected Clock")),
                    List.of("Lazy expiry on read (no background job) - simple, but expired rows stay until a cleanup job is added"),
                    List.of("ShortUrl.expiresAt"),
                    List.of(),
                    List.of("ADR: 410 Gone (not 404) for expired/disabled links so clients can tell 'existed' from 'never existed'"),
                    new Impact(List.of(), List.of(), List.of(), null, "expiresAt")),
            new Feature("alias", "Custom aliases",
                    p("alias|custom (code|slug)|vanity"),
                    List.of("A custom alias (4-32 chars of [A-Za-z0-9_-]) is honoured; a taken alias returns 409 and reserved words are rejected"),
                    List.of(new RiskSpec("security", "Aliases could shadow API or infrastructure routes",
                            RiskLevel.LOW, "Reserved-word list (api, actuator, admin...) and strict character set")),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    new Impact(List.of(), List.of(), List.of(), null, "validateAlias")),
            new Feature("ratelimit", "Rate limiting",
                    p("rate[- ]?limit|throttl|abuse"),
                    List.of("Link creation is limited per client with a token bucket; excess requests receive 429 with Retry-After"),
                    List.of(new RiskSpec("scalability", "In-process buckets allow N x the limit behind a load balancer with N replicas",
                            RiskLevel.MEDIUM, "Redis-backed bucket behind the same interface when scaling out")),
                    List.of("In-memory limiter: zero infra, but per-instance"),
                    List.of("TokenBucketRateLimiter"),
                    List.of(),
                    List.of(),
                    new Impact(List.of(), List.of(), List.of(), null, "TokenBucketRateLimiter")),
            new Feature("ssrf", "Unsafe target protection",
                    p("ssrf|private[- ]network|malicious|phishing|unsafe (url|target)|block private|safer|secure"),
                    List.of("Targets on loopback/private/link-local networks, URLs with embedded credentials and non-http(s) schemes are rejected with 400"),
                    List.of(new RiskSpec("security", "DNS rebinding: a public hostname can later resolve to a private IP",
                            RiskLevel.LOW, "Shortener never fetches targets itself; only literal IPs/hosts are checked at creation")),
                    List.of("No DNS resolution at creation time (latency, rebinding makes it unreliable)"),
                    List.of("UrlValidator"),
                    List.of(),
                    List.of(),
                    new Impact(List.of(), List.of(), List.of(), null, "isLocalOrPrivate")),
            new Feature("maxclicks", "Max-clicks limit",
                    p("max(imum)?[- ]?clicks|click[- ]limit|limit (the )?(number of )?(clicks|redirects)|(clicks|redirects) limit"),
                    List.of("A link created with maxClicks=N redirects at most N times; afterwards GET /{code} returns 410 Gone",
                            "Links created without maxClicks behave exactly as before (backward compatible API, schema and data)"),
                    List.of(new RiskSpec("consistency", "Check-then-act race: concurrent redirects can overshoot maxClicks by up to (concurrency - 1)",
                                    RiskLevel.MEDIUM, "Accept small overshoot for v1 (documented); v2: conditional UPDATE ... WHERE click_count < max_clicks"),
                            new RiskSpec("change", "Schema migration on a large, hot table",
                                    RiskLevel.MEDIUM, "Additive nullable column (metadata-only change), new V2 migration, no backfill"),
                            new RiskSpec("regression", "Changing isResolvable() affects every redirect",
                                    RiskLevel.MEDIUM, "Regression tests for expiry/deactivation paths; null maxClicks keeps old behaviour")),
                    List.of("Nullable column + optional request field keeps old clients working; a default limit would have broken them"),
                    List.of("ShortUrl.maxClicks", "CreateShortUrlRequest.maxClicks"),
                    List.of("POST /api/v1/urls (new optional field maxClicks)", "GET /{code} (410 when exhausted)"),
                    List.of("ADR: exhausted links return 410 Gone, consistent with expiry"),
                    new Impact(List.of("ShortUrl", "CreateCommand", "CreateShortUrlRequest"),
                            List.of("CreateCommand"), List.of("isResolvable", "resolve"),
                            "ALTER TABLE short_url ADD COLUMN max_clicks BIGINT", "maxClicks")),
            new Feature("cache", "Hot-link caching",
                    p("cach|latency|p9[059]|faster|speed up|performance"),
                    List.of("Redirect lookups for hot links are served from a bounded in-process cache with a short TTL; updates (e.g. deactivation) invalidate the entry immediately"),
                    List.of(new RiskSpec("consistency", "Stale reads: with several replicas a deactivated link can still redirect from another instance's cache until the TTL elapses",
                                    RiskLevel.HIGH, "Short TTL (30s) + invalidate-on-write locally; pub/sub invalidation before scaling out"),
                            new RiskSpec("capacity", "Unbounded cache growth under link-scanning traffic",
                                    RiskLevel.MEDIUM, "Bounded size with LRU eviction; only successful lookups are cached")),
                    List.of("In-process cache: no new infrastructure and sub-ms hits, but per-instance and eventually consistent across replicas"),
                    List.of("CachingShortUrlStore (decorator over ShortUrlStore)"),
                    List.of(),
                    List.of("ADR: cache as a decorator on the ShortUrlStore port - zero change to business rules, trivially removable"),
                    new Impact(List.of("ShortenerConfiguration"), List.of(), List.of("ShortUrlStore"), null, "CachingShortUrlStore")));

    public static List<Feature> detect(String text) {
        String t = text.toLowerCase(Locale.ROOT);
        List<Feature> found = new ArrayList<>();
        for (Feature f : ALL) {
            if (f.matches(t)) found.add(f);
        }
        return found;
    }

    public static Optional<Feature> byName(String name) {
        return ALL.stream().filter(f -> f.name().equalsIgnoreCase(name) || f.key().equalsIgnoreCase(name)).findFirst();
    }

    /** Vague, unmeasurable terms and the question that makes each one testable. */
    public record VagueTerm(Pattern pattern, String term, String question) {
    }

    public static final List<VagueTerm> VAGUE_TERMS = List.of(
            new VagueTerm(p("\\b(fast|faster|quick|quicker|speed|performant)\\b"), "fast",
                    "What latency target (e.g. p95 redirect latency in ms) and at what load (requests/second)?"),
            new VagueTerm(p("\\b(secure|safer|safe|security)\\b"), "secure",
                    "Which threats are in scope: malicious/private-network targets (SSRF), abuse (rate limiting), link enumeration, or authentication of the management API?"),
            new VagueTerm(p("\\b(scalable|scale)\\b"), "scalable",
                    "What scale target: number of stored links and peak redirects per second?"),
            new VagueTerm(p("\\b(better|improve|improved|enhance)\\b"), "better",
                    "Which measurable outcome defines success for this improvement?"),
            new VagueTerm(p("\\b(robust|reliable|resilient)\\b"), "reliable",
                    "What availability target (e.g. 99.9%) and which failure modes must be tolerated?"),
            new VagueTerm(p("\\b(user[- ]friendly|easy|simple|nice)\\b"), "user-friendly",
                    "Who is the user and what concrete behaviour would make it easier for them?"));
}
