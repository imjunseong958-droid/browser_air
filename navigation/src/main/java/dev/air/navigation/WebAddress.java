package dev.air.navigation;

import java.net.IDN;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** URL parsing shared by every entry point and navigation delegate: exact up to the host, tolerant of what follows it. */
public record WebAddress(URI uri, String scheme, String host, int port) {
    private static final String ESCAPED_AFTER_HOST = "\"<>^`{|}[]\\";

    public static WebAddress parse(String input) {
        if (input == null || input.isBlank() || input.length() > 32_768
                || input.chars().anyMatch(c -> c <= 32 || c == 127)) {
            return null;
        }
        try {
            URI uri = new URI(escapeAfterHost(input));
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("https") && !scheme.equals("http")) return null;
            String authority = uri.getRawAuthority();
            if (authority == null || authority.contains("@") || authority.contains("%")) return null;
            String host = uri.getHost();
            // java.net.URI does not return a host for Unicode domain names or names with an underscore.
            if (host == null) {
                host = authority;
                int colon = host.lastIndexOf(':');
                if (colon >= 0) host = host.substring(0, colon);
            }
            if (host.startsWith("[") && host.endsWith("]")) {
                host = host.toLowerCase(Locale.ROOT);
            } else {
                host = IDN.toASCII(host).toLowerCase(Locale.ROOT);
                if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
                if (!host.matches("[a-z0-9._-]+")) return null;
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

    /**
     * Browsers load addresses with characters java.net.URI refuses, such as "?ids=1|2" or a stray "%", so those are
     * escaped for reading only. A backslash before the query is left in to be refused: browsers read it as "/".
     */
    private static String escapeAfterHost(String input) {
        int start = input.indexOf("://");
        if (start < 0) return input;
        int end = start + 3;
        while (end < input.length() && "/?#\\".indexOf(input.charAt(end)) < 0) end++;
        StringBuilder escaped = new StringBuilder(input.length()).append(input, 0, end);
        boolean inPath = true;
        boolean inFragment = false;
        for (int i = end; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '\\' && inPath) return input;
            boolean strayPercent = c == '%' && !(i + 2 < input.length() && isHex(input.charAt(i + 1)) && isHex(input.charAt(i + 2)));
            if (strayPercent || ESCAPED_AFTER_HOST.indexOf(c) >= 0 || (c == '#' && inFragment)) {
                escaped.append(String.format(Locale.ROOT, "%%%02X", (int) c));
            } else {
                escaped.append(c);
            }
            if (c == '?' || c == '#') inPath = false;
            if (c == '#') inFragment = true;
        }
        return escaped.toString();
    }

    private static boolean isHex(char c) { return Character.digit(c, 16) >= 0 && c < 128; }

    public boolean sameOrigin(WebAddress other) {
        return other != null && scheme.equals(other.scheme) && host.equals(other.host) && port == other.port;
    }

    public String path() {
        String path = uri.getPath();
        return path == null || path.isEmpty() ? "/" : path;
    }

    /** Resolve decoded dot segments before checking a path, so "/a/../shorts" cannot pass as "/a". */
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
