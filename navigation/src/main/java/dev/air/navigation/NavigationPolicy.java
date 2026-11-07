package dev.air.navigation;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * AIR refuses adult sites, communities, unfiltered search and YouTube's recommendation routes; every other web page
 * opens, whatever led to it. Search opens only as Google SafeSearch text results.
 */
public final class NavigationPolicy {
    public enum Decision {
        ALLOW, AUTH_CALLBACK, OPEN_APP, BLOCK_SEARCH, BLOCK_COMMUNITY, BLOCK_ADULT, BLOCK_RECOMMENDATION, BLOCK_INVALID
    }

    /** These belong to the engine or reach private files, so they never go to another app or carry a sign-in's result. */
    private static final Set<String> NOT_FOR_APPS = Set.of(
            "intent", "javascript", "data", "file", "content", "about", "blob", "ftp", "ws", "wss",
            "resource", "chrome", "jar", "view-source", "moz-extension");

    private SignIn signIn;
    private boolean settled;

    public NavigationPolicy(String initialUrl) {
        WebAddress entry = WebAddress.parse(resolveLink(initialUrl));
        if (entry == null || refused(entry) != null) throw new IllegalArgumentException("Invalid entry URL");
        noteSignIn(entry);
    }

    public static String resolveLink(String url) { return WebLinks.resolve(url); }

    /** The SafeSearch text results for typed terms, or null when there is nothing to search. */
    public static String searchUrl(String terms) { return WebLinks.searchUrl(terms); }

    /**
     * Checks a move from the page showing now. Pages such as YouTube change their own address without a load
     * request, so this also runs on every address change; it never rewrites the target.
     */
    public static Decision pageChange(String from, String to) {
        WebAddress target = WebAddress.parse(to);
        if (target == null) return Decision.ALLOW;
        Decision refused = refused(target);
        if (refused != null) return refused;
        return WebLinks.isRecommendationHop(WebAddress.parse(from), target) ? Decision.BLOCK_RECOMMENDATION : Decision.ALLOW;
    }

    public static Decision entryDecision(String url) {
        WebAddress address = WebAddress.parse(resolveLink(url));
        if (address == null) return Decision.BLOCK_INVALID;
        Decision refused = refused(address);
        return refused == null ? Decision.ALLOW : refused;
    }

    /** Checks a page's link, redirect, script or popup. */
    public Decision evaluate(String url) {
        url = resolveLink(url);
        if (url == null) return Decision.BLOCK_INVALID;
        if (runsInEngine(url)) return Decision.ALLOW;
        WebAddress target = WebAddress.parse(url);
        Decision refused = target == null ? null : refused(target);
        if (refused != null) return refused;
        if (isSignInReturn(url)) return Decision.AUTH_CALLBACK;
        if (target == null) return opensInApp(url) ? Decision.OPEN_APP : Decision.BLOCK_INVALID;
        if (!settled) noteSignIn(target);
        return Decision.ALLOW;
    }

    /** Frames are not rewritten, so only search results that are already safe may be embedded. */
    public static Decision evaluateFrame(String url) {
        if (runsInEngine(url)) return Decision.ALLOW;
        WebAddress target = WebAddress.parse(url);
        if (target == null) return Decision.BLOCK_INVALID;
        Decision refused = refused(target);
        return refused == null ? Decision.ALLOW : refused;
    }

    /** The first document has loaded, so sign-ins from here on are the page's own, not the opening app's. */
    public void pageLoaded() { settled = true; }

    /** The opening app's sign-in result had no verified app to go to and opens here as an ordinary page. */
    public void signInReturned() { signIn = null; }

    /** Content the engine draws from the page itself rather than fetching from a web address. */
    private static boolean runsInEngine(String url) {
        return url.equals("about:blank") || url.equals("about:srcdoc") || url.startsWith("blob:")
                || url.startsWith("data:") || url.startsWith("javascript:");
    }

    /** Phone numbers, mail, payment and other apps' links; no app can claim http(s) from here. */
    private static boolean opensInApp(String url) {
        int colon = url.indexOf(':');
        if (colon <= 0) return false;
        String scheme = url.substring(0, colon).toLowerCase(Locale.ROOT);
        return scheme.matches("[a-z][a-z0-9+.-]*") && !scheme.equals("http") && !scheme.equals("https")
                && !NOT_FOR_APPS.contains(scheme);
    }

    /** Adult sites, communities and the search and feed routes never open, whatever route reaches them. */
    private static Decision refused(WebAddress url) {
        if (WebLinks.isAdult(url)) return Decision.BLOCK_ADULT;
        if (WebLinks.isCommunity(url)) return Decision.BLOCK_COMMUNITY;
        return WebLinks.isDiscovery(url) ? Decision.BLOCK_SEARCH : null;
    }

    /** Remembers the OAuth sign-in an app opened AIR for, so that its result can go back to that app. */
    private void noteSignIn(WebAddress target) {
        Map<String, String> query = target.parameters();
        String redirectUri = query.get("redirect_uri");
        String client = query.get("client_id");
        if (signIn != null || !target.scheme().equals("https") || redirectUri == null || client == null || client.isBlank()
                || (!query.containsKey("response_type") && !query.containsKey("code_challenge"))
                || redirectUri.contains("\\")) return;
        try {
            URI callback = URI.create(redirectUri);
            if (!callback.isAbsolute() || callback.isOpaque() || callback.getRawFragment() != null || callback.getRawUserInfo() != null
                    || NOT_FOR_APPS.contains(callback.getScheme().toLowerCase(Locale.ROOT))) return;
            signIn = new SignIn(callback, query.get("state"));
        } catch (IllegalArgumentException ignored) { /* Not a sign-in this policy can return from. */ }
    }

    private boolean isSignInReturn(String url) {
        if (signIn == null) return false;
        try {
            URI target = URI.create(url);
            Map<String, String> parameters = callbackParameters(target);
            if (parameters == null || !hasAuthResult(target) || !sameEndpoint(signIn.callback, target)) return false;
            if (signIn.state != null && !signIn.state.equals(parameters.get("state"))) return false;
            return parameters.entrySet().containsAll(WebAddress.decodeParameters(signIn.callback.getRawQuery()).entrySet());
        } catch (IllegalArgumentException ignored) { return false; }
    }

    private static Map<String, String> callbackParameters(URI target) {
        Map<String, String> query = WebAddress.decodeParameters(target.getRawQuery());
        Map<String, String> fragment = WebAddress.decodeParameters(target.getRawFragment());
        if (target.getRawQuery() != null && !target.getRawQuery().isEmpty() && query.isEmpty()) return null;
        if (target.getRawFragment() != null && !target.getRawFragment().isEmpty() && fragment.isEmpty()) return null;
        Map<String, String> combined = new LinkedHashMap<>(query);
        for (Map.Entry<String, String> part : fragment.entrySet()) {
            if (combined.putIfAbsent(part.getKey(), part.getValue()) != null) return null;
        }
        return combined;
    }

    private static boolean hasAuthResult(URI target) {
        Map<String, String> query = WebAddress.decodeParameters(target.getRawQuery());
        Map<String, String> fragment = WebAddress.decodeParameters(target.getRawFragment());
        return query.containsKey("code") || query.containsKey("error") || query.containsKey("access_token") || query.containsKey("id_token")
                || fragment.containsKey("code") || fragment.containsKey("error") || fragment.containsKey("access_token") || fragment.containsKey("id_token");
    }

    private static boolean sameEndpoint(URI expected, URI actual) {
        if (expected.getScheme() == null || actual.getScheme() == null
                || !expected.getScheme().equalsIgnoreCase(actual.getScheme())) return false;
        WebAddress a = WebAddress.parse(expected.toString());
        WebAddress b = WebAddress.parse(actual.toString());
        if (a != null || b != null) return a != null && a.sameOrigin(b) && a.path().equals(b.path());
        return Objects.equals(expected.getAuthority(), actual.getAuthority()) && Objects.equals(expected.getPath(), actual.getPath());
    }

    private record SignIn(URI callback, String state) {}
}
