package com.example.shortener.service;

import java.security.SecureRandom;
import java.util.random.RandomGenerator;

/**
 * Generates random Base62 codes.
 * <p>
 * Design decision: random codes instead of sequential Base62(id) so that codes are not enumerable
 * (prevents scraping of all links). 7 chars of Base62 = 62^7 ~ 3.5e12 keyspace; collisions are
 * handled by a bounded retry in {@link ShortUrlService}.
 */
public class CodeGenerator {

    static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    private final RandomGenerator random;
    private final int length;

    public CodeGenerator(int length) {
        this(new SecureRandom(), length);
    }

    public CodeGenerator(RandomGenerator random, int length) {
        if (length < 4 || length > 32) {
            throw new IllegalArgumentException("code length must be between 4 and 32");
        }
        this.random = random;
        this.length = length;
    }

    public String next() {
        char[] out = new char[length];
        for (int i = 0; i < length; i++) {
            out[i] = ALPHABET.charAt(random.nextInt(ALPHABET.length()));
        }
        return new String(out);
    }

    public int length() {
        return length;
    }
}
