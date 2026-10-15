package dev.air.navigation;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Classifies destinations, rather than treating every page on a search provider as search. */
final class WebLinks {
    private static final Set<String> GOOGLE_LINK_HOSTS = Set.of(
            "google.com", "www.google.com", "google.co.kr", "www.google.co.kr");

    private WebLinks() {}

    /** Resolve only a recognized link wrapper. Never execute its fallback or an arbitrary scheme. */
    static String resolve(String input) {
        for (int depth = 0; depth <= 4; depth++) {
            WebAddress address = WebAddress.parse(input);
            if (address == null || !GOOGLE_LINK_HOSTS.contains(address.host())
                    || !address.normalizedPath().equals("/url")) return input;
            if (depth == 4 || !address.scheme().equals("https") || address.port() != 443) return null;
            Map<String, String> query = address.parameters();
            String destination = query.getOrDefault("url", query.get("q"));
            if (destination == null || (query.containsKey("url") && query.containsKey("q")
                    && !query.get("url").equals(query.get("q"))) || WebAddress.parse(destination) == null) return null;
            input = destination;
        }
        return null;
    }

    static boolean isDiscovery(WebAddress address) {
        String host = address.host().startsWith("www.") ? address.host().substring(4) : address.host();
        String path = address.normalizedPath().toLowerCase(Locale.ROOT);
        if (host.startsWith("google.")) {
            return path.equals("/") || at(path, "/search") || at(path, "/webhp")
                    || at(path, "/imghp") || at(path, "/images") || at(path, "/videohp")
                    || at(path, "/shopping") || at(path, "/discover") || at(path, "/recaptcha");
        }
        if (Set.of("bing.com", "cn.bing.com").contains(host)) {
            return path.equals("/") || at(path, "/search") || at(path, "/images")
                    || at(path, "/videos") || at(path, "/news");
        }
        if (Set.of("naver.com", "m.naver.com", "daum.net", "m.daum.net").contains(host)) {
            return path.equals("/") || path.startsWith("/search") || at(path, "/feed");
        }
        if (Set.of("duckduckgo.com", "html.duckduckgo.com", "lite.duckduckgo.com",
                "search.yahoo.com", "search.naver.com", "m.search.naver.com", "search.daum.net",
                "search.brave.com", "ecosia.org", "yandex.com", "yandex.ru", "baidu.com").contains(host)) return true;
        if (Set.of("youtube.com", "m.youtube.com").contains(host)) {
            return path.equals("/") || at(path, "/feed") || at(path, "/results") || at(path, "/shorts");
        }
        if (Set.of("x.com", "twitter.com", "mobile.twitter.com").contains(host)) {
            return path.equals("/") || at(path, "/home") || at(path, "/explore") || at(path, "/search");
        }
        if (host.equals("github.com")) return at(path, "/search") || at(path, "/explore") || at(path, "/trending");
        if (host.equals("docs.google.com")) {
            return path.equals("/") || path.matches("/(?:forms|document|spreadsheets|presentation)(?:/u/\\d+)?/?");
        }
        if (host.equals("drive.google.com")) {
            return path.equals("/") || path.matches("/drive(?:/u/\\d+)?(?:/(?:home|my-drive|shared-with-me|recent|search))?/?");
        }
        return false;
    }

    private static boolean at(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }
}
