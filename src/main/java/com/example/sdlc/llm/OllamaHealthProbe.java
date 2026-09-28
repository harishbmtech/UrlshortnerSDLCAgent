package com.example.sdlc.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks whether the Ollama server is up and which models it has pulled ({@code GET /api/tags}).
 * <p>
 * The result is cached for a short time so agents can ask on every call without adding latency. If Ollama
 * goes down mid-run, agents switch to their deterministic strategies within one cache period. When it comes
 * back, they switch back to the LLM. The app never fails to start because Ollama is missing.
 */
public class OllamaHealthProbe {

    private static final Logger log = LoggerFactory.getLogger(OllamaHealthProbe.class);
    private static final Pattern MODEL_NAME = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");

    public record Status(boolean reachable, Set<String> installedModels, String error, Instant checkedAt) {
    }

    private final URI baseUrl;
    private final Duration cacheTtl;
    private final Duration timeout;
    private final Clock clock;
    private final HttpClient http;
    private volatile Status cached;

    public OllamaHealthProbe(String baseUrl, Duration cacheTtl) {
        this(baseUrl, cacheTtl, Duration.ofSeconds(2), Clock.systemUTC());
    }

    public OllamaHealthProbe(String baseUrl, Duration cacheTtl, Duration timeout, Clock clock) {
        this.baseUrl = URI.create(baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl);
        this.cacheTtl = cacheTtl;
        this.timeout = timeout;
        this.clock = clock;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    public String baseUrl() {
        return baseUrl.toString();
    }

    public Status status() {
        Status s = cached;
        if (s == null || s.checkedAt().plus(cacheTtl).isBefore(clock.instant())) {
            s = refresh();
        }
        return s;
    }

    public synchronized Status refresh() {
        Status previous = cached;
        Status next;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/api/tags")).timeout(timeout).GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) {
                next = new Status(false, Set.of(), "HTTP " + res.statusCode() + " from /api/tags", clock.instant());
            } else {
                next = new Status(true, parseModelNames(res.body()), null, clock.instant());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            next = new Status(false, Set.of(), "interrupted", clock.instant());
        } catch (Exception e) {
            next = new Status(false, Set.of(), e.getClass().getSimpleName() + ": " + e.getMessage(), clock.instant());
        }
        if (previous == null || previous.reachable() != next.reachable()) {
            if (next.reachable()) {
                log.info("Ollama reachable at {} with models {}", baseUrl, next.installedModels());
            } else {
                log.warn("Ollama not reachable at {} ({}). Agents use deterministic strategies until it is back.",
                        baseUrl, next.error());
            }
        }
        cached = next;
        return next;
    }

    /** True if the model is pulled. {@code llama3.1} and {@code llama3.1:latest} are the same model. */
    public boolean isInstalled(String model) {
        Set<String> installed = status().installedModels();
        return installed.contains(normalize(model));
    }

    static Set<String> parseModelNames(String tagsJson) {
        Set<String> names = new LinkedHashSet<>();
        Matcher m = MODEL_NAME.matcher(tagsJson == null ? "" : tagsJson);
        while (m.find()) {
            names.add(normalize(m.group(1)));
        }
        return names;
    }

    static String normalize(String model) {
        return model.contains(":") ? model : model + ":latest";
    }
}
