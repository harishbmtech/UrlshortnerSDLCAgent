package com.example.sdlc.agents.offline;

import com.example.sdlc.agents.AgentDtos.GeneratedFile;
import com.example.sdlc.model.Enums.ArtifactType;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Deterministic code generation used when no LLM is configured (or as the fallback when the LLM fails).
 * <ul>
 *   <li>Greenfield: replays the curated reference implementation shipped in this repository.</li>
 *   <li>Brownfield: applies anchored, verifiable patches for known features; if an anchor is missing the
 *       patch fails loudly ({@link PatchConflictException}) instead of guessing.</li>
 * </ul>
 */
public final class CodeTemplates {

    private CodeTemplates() {
    }

    // ------------------------------------------------------------------ greenfield

    public static List<GeneratedFile> referenceImplementation(Path sourceRoot, Path migrationsRoot) {
        List<GeneratedFile> out = new ArrayList<>();
        Path testRoot = Path.of(sourceRoot.toString().replace("src/main/java", "src/test/java")
                .replace("src\\main\\java", "src\\test\\java"));
        collect(sourceRoot, ".java", ArtifactType.CODE, out);
        collect(testRoot, ".java", ArtifactType.TEST, out);
        collect(migrationsRoot, ".sql", ArtifactType.MIGRATION, out);
        return out;
    }

    private static void collect(Path root, String ext, ArtifactType type, List<GeneratedFile> out) {
        if (root == null || !Files.isDirectory(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.filter(f -> f.toString().endsWith(ext)).sorted().toList()) {
                out.add(new GeneratedFile(rel(p), type, Files.readString(p, StandardCharsets.UTF_8)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String rel(Path p) {
        Path cwd = Path.of("").toAbsolutePath();
        Path abs = p.toAbsolutePath().normalize();
        return (abs.startsWith(cwd) ? cwd.relativize(abs) : p).toString().replace('\\', '/');
    }

    // ------------------------------------------------------------------ brownfield: max-clicks

    /**
     * @param pathOf  resolves a type name to its project-relative path
     * @param read    returns the current content of a path
     */
    public static List<GeneratedFile> maxClicks(Function<String, Optional<String>> pathOf,
                                                Function<String, Optional<String>> read,
                                                String migrationsDir, int migrationVersion) {
        List<GeneratedFile> out = new ArrayList<>();

        String entityPath = require(pathOf, "ShortUrl");
        String entity = require(read, entityPath);
        entity = patch(entityPath, entity, "    private boolean active = true;\n",
                """
                    private boolean active = true;

                    /** Optional redirect budget; null = unlimited (keeps existing links backward compatible). */
                    @Column(name = "max_clicks")
                    private Long maxClicks;
                """);
        entity = patch(entityPath, entity, """
                    public boolean isResolvable(Instant now) {
                        return active && !isExpired(now);
                    }
                """, """
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
                """);
        entity = patch(entityPath, entity, "    public boolean isActive() { return active; }\n",
                "    public boolean isActive() { return active; }\n    public Long getMaxClicks() { return maxClicks; }\n");
        out.add(new GeneratedFile(entityPath, ArtifactType.CODE, entity));

        String servicePath = require(pathOf, "CreateCommand");
        String service = require(read, servicePath);
        service = patch(servicePath, service, """
                    public record CreateCommand(String url, String alias, Long ttlSeconds) {
                    }
                """, """
                    public record CreateCommand(String url, String alias, Long ttlSeconds, Long maxClicks) {

                        /** Backward-compatible constructor for callers that do not use a click budget. */
                        public CreateCommand(String url, String alias, Long ttlSeconds) {
                            this(url, alias, ttlSeconds, null);
                        }
                    }
                """);
        service = patch(servicePath, service, "        Instant expiresAt = resolveExpiry(cmd.ttlSeconds(), now);\n", """
                        Instant expiresAt = resolveExpiry(cmd.ttlSeconds(), now);
                        if (cmd.maxClicks() != null && cmd.maxClicks() <= 0) {
                            throw new InvalidRequestException("maxClicks must be positive");
                        }
                """);
        service = patch(servicePath, service, "store.insert(new ShortUrl(alias, target, now, expiresAt))",
                "store.insert(newLink(alias, target, now, expiresAt, cmd.maxClicks()))");
        service = patch(servicePath, service, "store.insert(new ShortUrl(code, target, now, expiresAt))",
                "store.insert(newLink(code, target, now, expiresAt, cmd.maxClicks()))");
        service = patch(servicePath, service, "    private Instant resolveExpiry(", """
                    private static ShortUrl newLink(String code, String target, Instant now, Instant expiresAt, Long maxClicks) {
                        ShortUrl link = new ShortUrl(code, target, now, expiresAt);
                        link.limitClicks(maxClicks);
                        return link;
                    }

                """ + "    private Instant resolveExpiry(");
        out.add(new GeneratedFile(servicePath, ArtifactType.CODE, service));

        String dtoPath = require(pathOf, "CreateShortUrlRequest");
        String dtos = require(read, dtoPath);
        dtos = patch(dtoPath, dtos, """
                    public record CreateShortUrlRequest(
                            @NotBlank @Size(max = 2048) String url,
                            @Size(min = 4, max = 32) String alias,
                            @Positive Long ttlSeconds) {
                    }
                """, """
                    public record CreateShortUrlRequest(
                            @NotBlank @Size(max = 2048) String url,
                            @Size(min = 4, max = 32) String alias,
                            @Positive Long ttlSeconds,
                            @Positive Long maxClicks) {

                        /** Backward-compatible constructor (clients that do not send maxClicks). */
                        public CreateShortUrlRequest(String url, String alias, Long ttlSeconds) {
                            this(url, alias, ttlSeconds, null);
                        }
                    }
                """);
        out.add(new GeneratedFile(dtoPath, ArtifactType.CODE, dtos));

        String controllerPath = require(pathOf, "ShortUrlController");
        String controller = require(read, controllerPath);
        controller = patch(controllerPath, controller, "request.url(), request.alias(), request.ttlSeconds()));",
                "request.url(), request.alias(), request.ttlSeconds(), request.maxClicks()));");
        out.add(new GeneratedFile(controllerPath, ArtifactType.CODE, controller));

        out.add(new GeneratedFile(migrationsDir + "/V" + migrationVersion + "__add_max_clicks_to_short_url.sql",
                ArtifactType.MIGRATION, """
                -- Additive, nullable column: metadata-only change, no backfill, old rows keep unlimited behaviour.
                ALTER TABLE short_url ADD COLUMN max_clicks BIGINT;
                """));

        String servicePkg = packageOf(servicePath);
        String testPath = servicePath.replace("src/main/java", "src/test/java")
                .replaceAll("[^/]+\\.java$", "MaxClicksLimitTest.java");
        out.add(new GeneratedFile(testPath, ArtifactType.TEST, MAX_CLICKS_TEST.replace("{{PKG}}", servicePkg)
                .replace("{{ROOT}}", parentPackage(servicePkg))));
        return out;
    }

    private static final String MAX_CLICKS_TEST = """
            package {{PKG}};

            import {{ROOT}}.domain.ShortUrl;
            import {{ROOT}}.domain.ShortenerExceptions.InvalidRequestException;
            import {{ROOT}}.domain.ShortenerExceptions.LinkGoneException;
            import {{ROOT}}.support.InMemoryShortUrlStore;
            import {{ROOT}}.support.MutableClock;
            import org.junit.jupiter.api.Test;

            import java.time.Duration;
            import java.time.Instant;
            import java.util.Set;

            import static org.junit.jupiter.api.Assertions.assertEquals;
            import static org.junit.jupiter.api.Assertions.assertNull;
            import static org.junit.jupiter.api.Assertions.assertThrows;

            /** Generated by the SDLC orchestrator for the max-clicks feature (AC traceability in the test plan). */
            class MaxClicksLimitTest {

                private final ShortUrlService service = new ShortUrlService(new InMemoryShortUrlStore(),
                        new UrlValidator(Set.of(), ""), new CodeGenerator(7),
                        new MutableClock(Instant.parse("2026-01-01T00:00:00Z")),
                        new ShortUrlService.Policy(null, Duration.ofDays(30), 5, Set.of(), "salt"));

                private final ShortUrlService.ClickContext ctx = new ShortUrlService.ClickContext(null, "JUnit", "203.0.113.1");

                @Test
                void linkStopsRedirectingAfterMaxClicks() {
                    ShortUrl link = service.create(new ShortUrlService.CreateCommand("https://example.com", null, null, 2L));

                    assertEquals("https://example.com", service.resolve(link.getCode(), ctx));
                    assertEquals("https://example.com", service.resolve(link.getCode(), ctx));
                    assertThrows(LinkGoneException.class, () -> service.resolve(link.getCode(), ctx));
                }

                @Test
                void linksWithoutBudgetBehaveAsBefore() {
                    ShortUrl link = service.create(new ShortUrlService.CreateCommand("https://example.com", null, null));

                    assertNull(link.getMaxClicks());
                    for (int i = 0; i < 10; i++) {
                        assertEquals("https://example.com", service.resolve(link.getCode(), ctx));
                    }
                }

                @Test
                void nonPositiveBudgetIsRejected() {
                    assertThrows(InvalidRequestException.class,
                            () -> service.create(new ShortUrlService.CreateCommand("https://example.com", null, null, 0L)));
                }
            }
            """;

    // ------------------------------------------------------------------ brownfield: caching

    public static List<GeneratedFile> caching(Function<String, Optional<String>> pathOf,
                                              Function<String, Optional<String>> read) {
        List<GeneratedFile> out = new ArrayList<>();
        String storePortPath = require(pathOf, "ShortUrlStore");
        String adapterPath = pathOf.apply("JpaShortUrlStore").orElse(storePortPath);
        String cachePath = adapterPath.replaceAll("[^/]+\\.java$", "CachingShortUrlStore.java");
        String cachePkg = packageOf(cachePath);
        String root = parentPackage(packageOf(storePortPath));

        out.add(new GeneratedFile(cachePath, ArtifactType.CODE,
                CACHE_CLASS.replace("{{PKG}}", cachePkg).replace("{{ROOT}}", root)));

        String configPath = require(pathOf, "ShortenerConfiguration");
        String config = require(read, configPath);
        config = patch(configPath, config, "import java.time.Clock;\n",
                "import java.time.Clock;\nimport java.time.Duration;\n");
        config = patch(configPath, config, "import org.springframework.boot.autoconfigure",
                "import " + cachePkg + ".CachingShortUrlStore;\nimport org.springframework.boot.autoconfigure");
        config = patch(configPath, config, "        return new ShortUrlService(store, validator, generator, clock, policy);\n", """
                        // Hot-link cache in front of the store (decorator). The TTL bounds cross-instance staleness.
                        ShortUrlStore cached = new CachingShortUrlStore(store, Duration.ofSeconds(30), 10_000, clock);
                        return new ShortUrlService(cached, validator, generator, clock, policy);
                """);
        out.add(new GeneratedFile(configPath, ArtifactType.CODE, config));

        String testPath = cachePath.replace("src/main/java", "src/test/java")
                .replace("CachingShortUrlStore.java", "CachingShortUrlStoreTest.java");
        out.add(new GeneratedFile(testPath, ArtifactType.TEST,
                CACHE_TEST.replace("{{PKG}}", cachePkg).replace("{{ROOT}}", root)));
        return out;
    }

    private static final String CACHE_CLASS = """
            package {{PKG}};

            import {{ROOT}}.domain.ClickEvent;
            import {{ROOT}}.domain.LinkStats;
            import {{ROOT}}.domain.ShortUrl;
            import {{ROOT}}.service.ShortUrlStore;

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
            """;

    private static final String CACHE_TEST = """
            package {{PKG}};

            import {{ROOT}}.domain.ShortUrl;
            import {{ROOT}}.support.InMemoryShortUrlStore;
            import {{ROOT}}.support.MutableClock;
            import org.junit.jupiter.api.Test;

            import java.time.Duration;
            import java.time.Instant;
            import java.util.Optional;
            import java.util.concurrent.atomic.AtomicInteger;

            import static org.junit.jupiter.api.Assertions.assertEquals;
            import static org.junit.jupiter.api.Assertions.assertFalse;
            import static org.junit.jupiter.api.Assertions.assertTrue;

            /** Generated by the SDLC orchestrator for the hot-link caching feature. */
            class CachingShortUrlStoreTest {

                private final AtomicInteger loads = new AtomicInteger();
                private final InMemoryShortUrlStore backing = new InMemoryShortUrlStore() {
                    @Override
                    public Optional<ShortUrl> findByCode(String code) {
                        loads.incrementAndGet();
                        return super.findByCode(code);
                    }
                };
                private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
                private final CachingShortUrlStore cache = new CachingShortUrlStore(backing, Duration.ofSeconds(30), 2, clock);

                @Test
                void repeatedLookupsHitTheCacheUntilTtlExpires() {
                    backing.insert(new ShortUrl("hot0001", "https://example.com", clock.instant(), null));

                    cache.findByCode("hot0001");
                    cache.findByCode("hot0001");
                    assertEquals(1, loads.get());

                    clock.advance(Duration.ofSeconds(31));
                    cache.findByCode("hot0001");
                    assertEquals(2, loads.get());
                }

                @Test
                void updatesInvalidateImmediately() {
                    ShortUrl link = backing.insert(new ShortUrl("hot0002", "https://example.com", clock.instant(), null));
                    cache.findByCode("hot0002");

                    link.deactivate();
                    cache.update(link);
                    cache.findByCode("hot0002");

                    assertEquals(2, loads.get());
                    assertFalse(cache.findByCode("hot0002").orElseThrow().isActive());
                }

                @Test
                void missesAreNotCachedAndSizeIsBounded() {
                    assertTrue(cache.findByCode("missing").isEmpty());
                    backing.insert(new ShortUrl("missing", "https://example.com", clock.instant(), null));
                    assertTrue(cache.findByCode("missing").isPresent());

                    backing.insert(new ShortUrl("aaaa001", "https://example.com", clock.instant(), null));
                    backing.insert(new ShortUrl("aaaa002", "https://example.com", clock.instant(), null));
                    cache.findByCode("aaaa001");
                    cache.findByCode("aaaa002");
                    assertEquals(2, cache.size());
                }
            }
            """;

    // ------------------------------------------------------------------ generic + fault injection

    /** For features with no deterministic template: an explicit change proposal instead of fake code. */
    public static GeneratedFile changeProposal(String feature, String requirement) {
        String slug = feature.toLowerCase().replaceAll("[^a-z0-9]+", "-");
        return new GeneratedFile("docs/proposals/" + slug + ".md", ArtifactType.DOC, """
                # Change proposal: %s

                Request: %s

                The offline (deterministic) developer has no verified template for this feature, so it does not
                emit speculative code. Start Ollama with the coder model pulled (see README) to generate an
                implementation; it will pass through the same policy gates and human approvals.
                """.formatted(feature, requirement));
    }

    /** Fault injection: code that the policy engine must reject (hard-coded secret + SQL concatenation). */
    public static GeneratedFile insecureSample(String sourceRoot) {
        String pkg = packageOf(sourceRoot + "/persistence/X.java");
        return new GeneratedFile(sourceRoot + "/persistence/LegacyLinkLookup.java", ArtifactType.CODE, """
                package %s;

                import java.sql.Connection;
                import java.sql.DriverManager;
                import java.sql.ResultSet;

                class LegacyLinkLookup {
                    private static final String PASSWORD = "Sup3rS3cret!";

                    String find(String code) throws Exception {
                        try (Connection c = DriverManager.getConnection("jdbc:h2:mem:x", "sa", PASSWORD)) {
                            ResultSet rs = c.createStatement().executeQuery("SELECT target_url FROM short_url WHERE code = '" + code + "'");
                            return rs.next() ? rs.getString(1) : null;
                        }
                    }
                }
                """.formatted(pkg));
    }

    // ------------------------------------------------------------------ helpers

    static String patch(String path, String content, String anchor, String replacement) {
        String lineEnding = content.contains("\r\n") ? "\r\n" : "\n";
        String sourceAnchor = anchor.replace("\r\n", "\n").replace("\n", lineEnding);
        String sourceReplacement = replacement.replace("\r\n", "\n").replace("\n", lineEnding);
        if (!content.contains(sourceAnchor)) {
            throw new PatchConflictException(path, anchor);
        }
        return content.replace(sourceAnchor, sourceReplacement);
    }

    private static String require(Function<String, Optional<String>> fn, String key) {
        return fn.apply(key).orElseThrow(() -> new PatchConflictException(key, "type/file not found in codebase: " + key));
    }

    public static String packageOf(String path) {
        String p = path.replace('\\', '/');
        int i = p.indexOf("src/main/java/");
        int j = p.indexOf("src/test/java/");
        int start = i >= 0 ? i + 14 : (j >= 0 ? j + 14 : 0);
        int end = p.lastIndexOf('/');
        return p.substring(start, end).replace('/', '.');
    }

    private static String parentPackage(String pkg) {
        return pkg.contains(".") ? pkg.substring(0, pkg.lastIndexOf('.')) : pkg;
    }
}
