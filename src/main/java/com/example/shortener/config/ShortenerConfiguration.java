package com.example.shortener.config;

import com.example.shortener.service.CodeGenerator;
import com.example.shortener.service.ShortUrlService;
import com.example.shortener.service.ShortUrlStore;
import com.example.shortener.service.TokenBucketRateLimiter;
import com.example.shortener.service.UrlValidator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.time.Clock;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Wires the framework-free shortener domain into Spring.
 */
@Configuration
public class ShortenerConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public UrlValidator urlValidator(ShortenerProperties props) {
        String ownHost = URI.create(props.baseUrl()).getHost();
        // localhost is already blocked as a private target; only treat real domains as "own host"
        return new UrlValidator(props.blockedDomains(), "localhost".equals(ownHost) ? "" : ownHost);
    }

    @Bean
    public CodeGenerator codeGenerator(ShortenerProperties props) {
        return new CodeGenerator(props.codeLength());
    }

    @Bean
    public TokenBucketRateLimiter createRateLimiter(ShortenerProperties props, Clock clock) {
        return new TokenBucketRateLimiter(props.rateLimit().capacity(), props.rateLimit().refillPerMinute(), clock);
    }

    @Bean
    public ShortUrlService shortUrlService(ShortUrlStore store, UrlValidator validator, CodeGenerator generator,
                                           Clock clock, ShortenerProperties props) {
        var reserved = props.reservedAliases().stream()
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        var policy = new ShortUrlService.Policy(props.defaultTtl(), props.maxTtl(), 5, reserved, props.clientHashSalt());
        return new ShortUrlService(store, validator, generator, clock, policy);
    }
}
