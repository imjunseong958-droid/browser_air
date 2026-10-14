package dev.air.navigation;

import java.net.IDN;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Strict URL parsing shared by every entry point and navigation delegate. */
public record WebAddress(URI uri, String scheme, String host, int port) {
    public static WebAddress parse(String input) {
        if (input == null || input.isBlank() || input.length() > 32_768
                || input.indexOf('\\') >= 0 || input.chars().anyMatch(c -> c <= 32 || c == 127)) {
            return null;
        }
        try {
            URI uri = new URI(input);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("https") && !scheme.equals("http")) return null;
            String authority = uri.getRawAuthority();
            if (authority == null || authority.contains("@") || authority.contains("%")) return null;
            String host = uri.getHost();
            // java.net.URI does not return a host for Unicode domain names.
            if (host == null) {
                host = authority;
                int colon = host.lastIndexOf(':');
                if (colon >= 0) host = host.substring(0, colon);
            }
            if (host.startsWith("[") && host.endsWith("]")) {
                host = host.toLowerCase(Locale.ROOT);
            } else {
                host = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
                if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
            }
            if (host.isBlank() || host.contains("..")) return null;
            int port = uri.getPort();
            if (port == -1 && !authority.startsWith("[") && authority.contains(":")) {
                port = Integer.parseInt(authority.substring(authority.lastIndexOf(':') + 1));
            }
            if (port < -1 || port > 65535 || port == 0) return null;
            return new WebAddress(uri, scheme, host, port == -1 ? defaultPort(scheme) : port);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static int defaultPort(String scheme) { return scheme.equals("https") ? 443 : 80; }

    private String siteHost() { return host.startsWith("www.") ? host.substring(4) : host; }

    /** Only the exact host and its www alias; sibling subdomains are separate sites. */
    public boolean sameSite(WebAddress other) {
        if (other == null || !siteHost().equals(other.siteHost())) return false;
        if (scheme.equals(other.scheme)) return port == other.port;
        // An HTTP entry may upgrade to HTTPS, never downgrade an HTTPS session.
        return scheme.equals("http") && port == 80
                && other.scheme.equals("https") && other.port == 443;
    }

    public boolean sameOrigin(WebAddress other) {
        return other != null && scheme.equals(other.scheme) && host.equals(other.host) && port == other.port;
    }

    public String path() {
        String path = uri.getPath();
        return path == null || path.isEmpty() ? "/" : path;
    }

    /** Resolve decoded dot segments before checking an authentication/CAPTCHA path. */
    public String normalizedPath() {
        try { return new URI(null, null, path(), null).normalize().getPath(); }
        catch (java.net.URISyntaxException ignored) { return ""; }
    }

    public Map<String, String> parameters() { return decodeParameters(uri.getRawQuery()); }

    static Map<String, String> decodeParameters(String encoded) {
        Map<String, String> values = new LinkedHashMap<>();
        if (encoded == null) return values;
        try {
            for (String part : encoded.split("&")) {
                String[] pair = part.split("=", 2);
                String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
                String value = URLDecoder.decode(pair.length == 2 ? pair[1] : "", StandardCharsets.UTF_8);
                // Ambiguous duplicate parameters are rejected rather than choosing a value.
                if (values.putIfAbsent(key, value) != null) return Map.of();
            }
            return values;
        } catch (IllegalArgumentException ignored) {
            return Map.of();
        }
    }
}
