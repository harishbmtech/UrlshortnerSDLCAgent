package com.example.shortener.domain;

/**
 * Domain exceptions. Each maps to exactly one HTTP status in the web layer (see GlobalExceptionHandler).
 */
public final class ShortenerExceptions {

    private ShortenerExceptions() {
    }

    /** 400 - target URL or alias is syntactically invalid or not allowed by policy. */
    public static class InvalidRequestException extends RuntimeException {
        public InvalidRequestException(String message) { super(message); }
    }

    /** 404 - unknown code. */
    public static class LinkNotFoundException extends RuntimeException {
        public LinkNotFoundException(String code) { super("Short link not found: " + code); }
    }

    /** 410 - link existed but is expired or deactivated. */
    public static class LinkGoneException extends RuntimeException {
        public LinkGoneException(String code) { super("Short link is expired or disabled: " + code); }
    }

    /** 409 - requested custom alias is taken. */
    public static class AliasConflictException extends RuntimeException {
        public AliasConflictException(String alias) { super("Alias already in use: " + alias); }
    }

    /** 429 - client exceeded its creation budget. */
    public static class RateLimitExceededException extends RuntimeException {
        private final long retryAfterSeconds;

        public RateLimitExceededException(long retryAfterSeconds) {
            super("Rate limit exceeded");
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public long retryAfterSeconds() { return retryAfterSeconds; }
    }

    /** Store-level signal that a unique code constraint was violated (race between check and insert). */
    public static class DuplicateCodeException extends RuntimeException {
        public DuplicateCodeException(String code, Throwable cause) { super("Duplicate code: " + code, cause); }
    }
}
