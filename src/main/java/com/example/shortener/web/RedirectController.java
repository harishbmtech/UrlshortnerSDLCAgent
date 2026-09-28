package com.example.shortener.web;

import com.example.shortener.service.ShortUrlService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * Hot path: {@code GET /{code}} -> 302 to the target.
 * <p>
 * Decision: 302 + {@code Cache-Control: no-store} rather than 301, so every click reaches us and is
 * counted, and so disabling/expiring a link takes effect immediately (a 301 is cached by browsers).
 */
@RestController
public class RedirectController {

    private final ShortUrlService service;

    public RedirectController(ShortUrlService service) {
        this.service = service;
    }

    @GetMapping("/{code:[A-Za-z0-9_-]+}")
    public ResponseEntity<Void> redirect(@PathVariable String code, HttpServletRequest request) {
        String target = service.resolve(code, new ShortUrlService.ClickContext(
                request.getHeader(HttpHeaders.REFERER),
                request.getHeader(HttpHeaders.USER_AGENT),
                request.getRemoteAddr()));
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(target))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .build();
    }
}
