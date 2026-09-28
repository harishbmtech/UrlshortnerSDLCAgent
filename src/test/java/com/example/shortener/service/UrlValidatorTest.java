package com.example.shortener.service;

import com.example.shortener.domain.ShortenerExceptions.InvalidRequestException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UrlValidatorTest {

    private final UrlValidator validator = new UrlValidator(Set.of("blocked.example"), "sho.rt");

    @Test
    void normalisesSchemeAndHost() {
        assertEquals("https://example.com/Path?q=1#frag",
                validator.validateAndNormalize("  HTTPS://EXAMPLE.com/Path?q=1#frag "));
    }

    @Test
    void acceptsPublicHostsAndPorts() {
        assertEquals("http://example.com:8081/x", validator.validateAndNormalize("http://example.com:8081/x"));
        assertEquals("https://8.8.8.8/", validator.validateAndNormalize("https://8.8.8.8/"));
    }

    @Test
    void rejectsUnsafeTargets() {
        List<String> bad = List.of(
                "javascript:alert(1)",
                "data:text/html,hi",
                "file:///etc/passwd",
                "ftp://example.com",
                "https://user:pass@example.com",
                "https://bank.com@evil.com",
                "http://localhost:8080/admin",
                "http://127.0.0.1/",
                "http://10.1.2.3/",
                "http://192.168.0.10/",
                "http://172.16.5.4/",
                "http://169.254.169.254/latest/meta-data",
                "http://100.64.0.1/",
                "http://[::1]/",
                "http://[fd00::1]/",
                "http://0.0.0.0/",
                "https://sub.blocked.example/",
                "https://sho.rt/loop",
                "not a url",
                "",
                "https://" + "a".repeat(2050) + ".com");
        for (String url : bad) {
            assertThrows(InvalidRequestException.class, () -> validator.validateAndNormalize(url), url);
        }
    }
}
