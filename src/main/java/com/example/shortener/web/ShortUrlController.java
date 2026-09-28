package com.example.shortener.web;

import com.example.shortener.config.ShortenerProperties;
import com.example.shortener.domain.ShortUrl;
import com.example.shortener.domain.ShortenerExceptions.RateLimitExceededException;
import com.example.shortener.service.ShortUrlService;
import com.example.shortener.service.TokenBucketRateLimiter;
import com.example.shortener.web.ShortUrlDtos.CreateShortUrlRequest;
import com.example.shortener.web.ShortUrlDtos.ShortUrlResponse;
import com.example.shortener.web.ShortUrlDtos.StatsResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * Management API for short links.
 */
@RestController
@RequestMapping("/api/v1/urls")
public class ShortUrlController {

    private final ShortUrlService service;
    private final TokenBucketRateLimiter rateLimiter;
    private final ShortenerProperties props;

    public ShortUrlController(ShortUrlService service, TokenBucketRateLimiter rateLimiter, ShortenerProperties props) {
        this.service = service;
        this.rateLimiter = rateLimiter;
        this.props = props;
    }

    @PostMapping
    public ResponseEntity<ShortUrlResponse> create(@Valid @RequestBody CreateShortUrlRequest request,
                                                   HttpServletRequest http) {
        // remoteAddr honours X-Forwarded-For only when server.forward-headers-strategy is configured
        // for a trusted proxy; trusting the raw header would let clients bypass the limit.
        long retryAfter = rateLimiter.tryAcquire(http.getRemoteAddr());
        if (retryAfter > 0) {
            throw new RateLimitExceededException(retryAfter);
        }
        ShortUrl created = service.create(new ShortUrlService.CreateCommand(
                request.url(), request.alias(), request.ttlSeconds()));
        ShortUrlResponse body = ShortUrlResponse.from(created, props.baseUrl());
        return ResponseEntity.created(URI.create("/api/v1/urls/" + created.getCode())).body(body);
    }

    @GetMapping("/{code}")
    public ShortUrlResponse get(@PathVariable String code) {
        return ShortUrlResponse.from(service.get(code), props.baseUrl());
    }

    @GetMapping("/{code}/stats")
    public StatsResponse stats(@PathVariable String code) {
        return StatsResponse.from(service.stats(code));
    }

    @DeleteMapping("/{code}")
    public ResponseEntity<Void> deactivate(@PathVariable String code) {
        service.deactivate(code);
        return ResponseEntity.noContent().build();
    }
}
