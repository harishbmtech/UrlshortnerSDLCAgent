package com.example.shortener.service;

import com.example.shortener.domain.ShortenerExceptions.InvalidRequestException;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates and normalises target URLs before they are shortened.
 * <p>
 * Guardrails (abuse / security):
 * <ul>
 *   <li>only http/https (blocks javascript:, data:, file: ...)</li>
 *   <li>no user-info (blocks {@code https://bank.com@evil.com} phishing style URLs)</li>
 *   <li>no loopback / private / link-local IP literals and no localhost (SSRF-style targets)</li>
 *   <li>no links back to the shortener's own domain (redirect loops)</li>
 *   <li>configurable deny-list of domains</li>
 *   <li>max length 2048</li>
 * </ul>
 * Trade-off: we deliberately do NOT resolve DNS at creation time (latency + DNS rebinding makes it
 * unreliable anyway). The shortener only issues a 302; it never fetches the target itself.
 */
public class UrlValidator {

    public static final int MAX_LENGTH = 2048;

    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");

    private final Set<String> blockedDomains;
    private final String ownHost;

    public UrlValidator(Set<String> blockedDomains, String ownHost) {
        this.blockedDomains = blockedDomains == null ? Set.of() : Set.copyOf(blockedDomains);
        this.ownHost = ownHost == null ? "" : ownHost.toLowerCase(Locale.ROOT);
    }

    /**
     * @return the normalised URL (trimmed, scheme and host lower-cased)
     * @throws InvalidRequestException when the URL is not acceptable
     */
    public String validateAndNormalize(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidRequestException("url must not be blank");
        }
        String candidate = raw.trim();
        if (candidate.length() > MAX_LENGTH) {
            throw new InvalidRequestException("url exceeds " + MAX_LENGTH + " characters");
        }
        URI uri;
        try {
            uri = new URI(candidate);
        } catch (URISyntaxException e) {
            throw new InvalidRequestException("url is not a valid URI");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new InvalidRequestException("only http and https URLs are allowed");
        }
        if (uri.getRawUserInfo() != null) {
            throw new InvalidRequestException("URLs with embedded credentials are not allowed");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new InvalidRequestException("url must contain a host");
        }
        host = host.toLowerCase(Locale.ROOT);
        if (isLocalOrPrivate(host)) {
            throw new InvalidRequestException("private, loopback and local addresses are not allowed");
        }
        if (!ownHost.isEmpty() && (host.equals(ownHost) || host.endsWith("." + ownHost))) {
            throw new InvalidRequestException("links to the shortener itself are not allowed");
        }
        for (String blocked : blockedDomains) {
            String b = blocked.toLowerCase(Locale.ROOT);
            if (host.equals(b) || host.endsWith("." + b)) {
                throw new InvalidRequestException("target domain is blocked by policy");
            }
        }
        try {
            URI normalized = new URI(scheme, uri.getRawUserInfo(), host, uri.getPort(),
                    uri.getPath(), uri.getQuery(), uri.getFragment());
            return normalized.toASCIIString();
        } catch (URISyntaxException e) {
            throw new InvalidRequestException("url is not a valid URI");
        }
    }

    static boolean isLocalOrPrivate(String host) {
        if (host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".local")
                || host.equals("0.0.0.0")) {
            return true;
        }
        String literal = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        boolean isIpLiteral = IPV4.matcher(literal).matches() || literal.contains(":");
        if (!isIpLiteral) {
            return false;
        }
        try {
            // Safe: for IP literals InetAddress does not perform a DNS lookup.
            InetAddress addr = InetAddress.getByName(literal);
            return addr.isLoopbackAddress() || addr.isSiteLocalAddress() || addr.isLinkLocalAddress()
                    || addr.isAnyLocalAddress() || addr.isMulticastAddress()
                    || isUniqueLocalV6(addr) || isCarrierGradeNat(addr);
        } catch (UnknownHostException e) {
            return true; // unparseable literal -> reject
        }
    }

    private static boolean isUniqueLocalV6(InetAddress addr) {
        byte[] b = addr.getAddress();
        return b.length == 16 && (b[0] & 0xFE) == 0xFC; // fc00::/7
    }

    private static boolean isCarrierGradeNat(InetAddress addr) {
        byte[] b = addr.getAddress();
        return b.length == 4 && (b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 64; // 100.64.0.0/10
    }
}
