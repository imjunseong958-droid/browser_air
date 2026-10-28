package dev.air.navigation;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Classifies destinations, rather than treating every page on a search provider as search. */
final class WebLinks {
    private static final Set<String> GOOGLE_LINK_HOSTS = Set.of(
            "google.com", "www.google.com", "google.co.kr", "www.google.co.kr");
    private static final String SAFE_SEARCH = "https://www.google.com/search";
    private static final Set<String> YOUTUBE_HOSTS = Set.of("youtube.com", "www.youtube.com", "m.youtube.com");
    /** Communities AIR never opens, including their subdomains. */
    private static final Set<String> COMMUNITY_DOMAINS = Set.of(
            "dcinside.com", "dcinside.co.kr", "fmkorea.com", "theqoo.net", "ruliweb.com", "instiz.net",
            "ilbe.com", "clien.net", "ppomppu.co.kr", "mlbpark.donga.com", "bobaedream.co.kr", "etoland.co.kr",
            "humoruniv.com", "todayhumor.co.kr", "inven.co.kr", "arca.live", "pann.nate.com", "82cook.com",
            "dogdrip.net", "ygosu.com", "slrclub.com", "damoang.net",
            "reddit.com", "redd.it", "4chan.org", "4channel.org", "9gag.com");

    private WebLinks() {}

    /** Resolve only a recognized link wrapper. Never execute its fallback or an arbitrary scheme. */
    static String resolve(String input) {
        for (int depth = 0; depth <= 4; depth++) {
            WebAddress address = WebAddress.parse(input);
            if (address == null || !GOOGLE_LINK_HOSTS.contains(address.host())) return input;
            if (address.normalizedPath().equals("/search")) return safeSearch(address, input);
            if (!address.normalizedPath().equals("/url")) return input;
            if (depth == 4 || !address.scheme().equals("https") || address.port() != 443) return null;
            Map<String, String> query = address.parameters();
            String destination = query.getOrDefault("url", query.get("q"));
            if (destination == null || (query.containsKey("url") && query.containsKey("q")
                    && !query.get("url").equals(query.get("q"))) || WebAddress.parse(destination) == null) return null;
            input = destination;
        }
        return null;
    }

    static String searchUrl(String terms) {
        if (terms == null || terms.isBlank()) return null;
        return SAFE_SEARCH + "?q=" + URLEncoder.encode(terms.strip(), StandardCharsets.UTF_8) + "&udm=14&safe=active";
    }

    static String youtubeSearchUrl(String terms) {
        if (terms == null || terms.isBlank()) return null;
        return "https://m.youtube.com/results?search_query=" + URLEncoder.encode(terms.strip(), StandardCharsets.UTF_8);
    }

    /** Going straight from one video to another follows a recommendation, unless both share a chosen playlist. */
    static boolean isRecommendationHop(WebAddress from, WebAddress to) {
        String current = youtubeVideo(from);
        String next = youtubeVideo(to);
        if (current == null || next == null || current.equals(next)) return false;
        String list = from.parameters().get("list");
        // RD playlists are YouTube's generated mixes rather than a list someone chose.
        return list == null || list.startsWith("RD") || !list.equals(to.parameters().get("list"));
    }

    private static String youtubeVideo(WebAddress address) {
        if (address == null) return null;
        String path = address.normalizedPath();
        if (address.host().equals("youtu.be")) return path.length() > 1 ? path.substring(1) : null;
        if (!YOUTUBE_HOSTS.contains(address.host()) || !path.equals("/watch")) return null;
        String video = address.parameters().get("v");
        return video == null || video.isBlank() ? null : video;
    }

    /** Google search opens only as SafeSearch text results; image, video and other tabs collapse into it. */
    private static String safeSearch(WebAddress address, String input) {
        Map<String, String> query = address.parameters();
        if (!address.scheme().equals("https") || address.port() != 443 || isSafeSearch(address)) return input;
        String terms = searchUrl(query.get("q"));
        if (terms == null) return input;
        String start = query.get("start");
        return start != null && start.matches("[1-9]\\d{0,2}") ? terms + "&start=" + start : terms;
    }

    private static boolean isSafeSearch(WebAddress address) {
        Map<String, String> query = address.parameters();
        String terms = query.get("q");
        return address.scheme().equals("https") && address.port() == 443 && GOOGLE_LINK_HOSTS.contains(address.host())
                && address.normalizedPath().equals("/search") && terms != null && !terms.isBlank()
                && "active".equals(query.get("safe")) && "14".equals(query.get("udm")) && !query.containsKey("tbm");
    }

    static boolean isCommunity(WebAddress address) {
        String host = address.host();
        for (String domain : COMMUNITY_DOMAINS) {
            if (host.equals(domain) || host.endsWith("." + domain)) return true;
        }
        return false;
    }

    static boolean isDiscovery(WebAddress address) {
        String host = address.host().startsWith("www.") ? address.host().substring(4) : address.host();
        String path = address.normalizedPath().toLowerCase(Locale.ROOT);
        if (host.startsWith("google.")) {
            return path.equals("/") || (at(path, "/search") && !isSafeSearch(address)) || at(path, "/webhp")
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
            // Search results stay open; the home feed, subscriptions and Shorts do not.
            return path.equals("/") || at(path, "/feed") || at(path, "/shorts");
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
