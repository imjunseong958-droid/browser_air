package dev.air.navigation;

import java.net.URI;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Search opens only as Google SafeSearch text results, and discovery feeds and communities stay blocked;
 * any other web destination becomes the current task.
 */
public final class NavigationPolicy {
    public enum Decision { ALLOW, AUTH_CALLBACK, BLOCK_SEARCH, BLOCK_COMMUNITY, BLOCK_SITE, BLOCK_INVALID, BLOCK_EXPIRED }

    private static final long AUTH_TIMEOUT_MS = 15 * 60 * 1000L;
    private static final int MAX_INITIAL_REDIRECTS = 8;
    private static final int MAX_AUTH_ORIGINS = 8;
    private static final Set<String> UNSAFE_CALLBACK_SCHEMES = Set.of(
            "intent", "javascript", "data", "file", "content", "about", "blob", "ftp");
    private static final Set<String> IDENTITY_HOSTS = Set.of(
            "accounts.google.com", "accounts.google.co.kr",
            "kauth.kakao.com", "accounts.kakao.com", "auth.kakao.com",
            "nid.naver.com", "id.naver.com",
            "login.microsoftonline.com", "login.live.com", "account.live.com",
            "appleid.apple.com", "idmsa.apple.com", "account.apple.com",
            "github.com", "www.facebook.com", "m.facebook.com", "login.yahoo.com",
            "api.twitter.com", "twitter.com", "x.com", "login.daum.net");

    private final LongSupplier clock;
    private WebAddress site;
    private TaskScope task;
    private WebAddress pendingInitial;
    private boolean settled;
    private int initialRedirects;
    private AuthTransaction auth;
    private WebLogin webLogin;
    private final List<AuthTransaction> transactions = new ArrayList<>();

    public NavigationPolicy(String initialUrl) {
        this(initialUrl, () -> System.nanoTime() / 1_000_000L);
    }

    public NavigationPolicy(String initialUrl, LongSupplier clock) {
        this.clock = clock;
        site = WebAddress.parse(resolveLink(initialUrl));
        if (site == null || isSearch(site) || isCommunity(site)) throw new IllegalArgumentException("Invalid entry URL");
        pendingInitial = site;
        task = new TaskScope(site);
        WebAddress returning = googleSignInReturn(site);
        if (returning != null && validWebReturn(returning)) {
            webLogin = new WebLogin(site, returning, clock.getAsLong());
            site = returning;
            task = new TaskScope(site);
        }
        registerAuthorization(pendingInitial, true);
    }

    public static String resolveLink(String url) { return WebLinks.resolve(url); }

    /** The SafeSearch text results for typed terms, or null when there is nothing to search. */
    public static String searchUrl(String terms) { return WebLinks.searchUrl(terms); }

    public static Decision entryDecision(String url) {
        WebAddress address = WebAddress.parse(resolveLink(url));
        if (address == null) return Decision.BLOCK_INVALID;
        if (isCommunity(address)) return Decision.BLOCK_COMMUNITY;
        return isSearch(address) ? Decision.BLOCK_SEARCH : Decision.ALLOW;
    }

    public String lockedHost() { return site.host(); }
    public boolean isAuthenticating() {
        return (auth != null && !authExpired()) || (webLogin != null && !webLoginExpired());
    }

    /** After an HTTPS app callback has no installed handler, render its exact web return destination. */
    public boolean completeWebCallback(String url) {
        WebAddress callback = WebAddress.parse(url);
        AuthTransaction returning = callbackTransaction(url);
        if (callback == null || returning == null || expired(returning)) return false;
        site = callback;
        task = new TaskScope(callback);
        pendingInitial = callback;
        settled = true;
        completeTransaction(returning);
        return true;
    }

    public Decision evaluate(String url, boolean redirect, boolean userGesture) {
        Decision decision = scoped(url, redirect, userGesture);
        if (decision != Decision.BLOCK_SITE && decision != Decision.BLOCK_EXPIRED) return decision;
        WebAddress target = WebAddress.parse(resolveLink(url));
        // Links, redirects and scripts may leave the task; a used or forged sign-in result may not.
        if (target == null || replaysAuthResult(target)) return decision;
        follow(target);
        return Decision.ALLOW;
    }

    /** The bounded task and authentication rules; their refusals only decide when the task moves. */
    private Decision scoped(String url, boolean redirect, boolean userGesture) {
        url = resolveLink(url);
        if (url == null) return Decision.BLOCK_INVALID;
        WebAddress target = WebAddress.parse(url);
        if (target != null && isCommunity(target)) return Decision.BLOCK_COMMUNITY;
        if (target != null && isSearch(target)) return Decision.BLOCK_SEARCH;
        AuthTransaction returning = callbackTransaction(url);
        if (returning != null) {
            if (expired(returning)) return Decision.BLOCK_EXPIRED;
            WebAddress callback = WebAddress.parse(url);
            if (callback != null && withinTask(callback)) {
                completeTransaction(returning);
                settled = true;
                return Decision.ALLOW;
            }
            return Decision.AUTH_CALLBACK;
        }
        if (target == null) return Decision.BLOCK_INVALID;
        if (replaysAuthResult(target)) return Decision.BLOCK_SITE;

        if (webLogin != null) {
            if (withinTask(target)) {
                if (webLoginExpired()) return Decision.BLOCK_EXPIRED;
                webLogin = null;
            } else if (isGoogleSignIn(target) && webLogin.provider.sameOrigin(target)) {
                if (webLoginExpired()) return Decision.BLOCK_EXPIRED;
                return googleSignInStepAllowed(target) ? Decision.ALLOW : Decision.BLOCK_SITE;
            } else return webLoginExpired() ? Decision.BLOCK_EXPIRED : Decision.BLOCK_SITE;
        }

        if (withinTask(target)) {
            registerAuthorization(target, false);
            if (!settled) pendingInitial = target;
            return Decision.ALLOW;
        }

        WebAddress webReturn = googleSignInReturn(target);
        if (auth == null && webLogin == null && webReturn != null && validWebReturn(webReturn)) {
            boolean shortForm = !settled && redirect && !userGesture && site.host().equals("forms.gle")
                    && webReturn.host().equals("docs.google.com") && new TaskScope(webReturn).isDocument()
                    && initialRedirects < MAX_INITIAL_REDIRECTS;
            if (withinTask(webReturn) || shortForm) {
                if (shortForm) {
                    initialRedirects++;
                    site = webReturn;
                    task = new TaskScope(site);
                }
                webLogin = new WebLogin(target, webReturn, clock.getAsLong());
                if (!settled) pendingInitial = target;
                return Decision.ALLOW;
            }
        }

        // Resolve only the original server redirect chain, before a document has loaded.
        if (!settled && redirect && initialRedirects++ < MAX_INITIAL_REDIRECTS) {
            if (pendingInitial.scheme().equals("https") && !target.scheme().equals("https")) {
                return Decision.BLOCK_SITE;
            }
            pendingInitial = target;
            if (auth != null && target.scheme().equals("https")) auth.origins.add(origin(target));
            registerAuthorization(target, true);
            if (auth == null && webLogin == null && !isAuthenticationPage(target)) {
                site = target;
                task = new TaskScope(target);
            }
            return Decision.ALLOW;
        }

        // OAuth requests may leave the site only when their return URI is scoped to this session.
        if (target.scheme().equals("https") && !authExpired() && registerAuthorization(target, false)) {
            return Decision.ALLOW;
        }
        if (auth != null) {
            if (authExpired()) return Decision.BLOCK_EXPIRED;
            if (target.scheme().equals("https") && auth.origins.contains(origin(target))
                    && isAuthenticationPage(target)) return Decision.ALLOW;
            // A server may federate to a new identity host; ordinary links cannot do so.
            if (redirect && !userGesture && target.scheme().equals("https")
                    && isAuthenticationPage(target) && auth.origins.size() < MAX_AUTH_ORIGINS) {
                auth.origins.add(origin(target));
                return Decision.ALLOW;
            }
        }
        return Decision.BLOCK_SITE;
    }

    private void follow(WebAddress target) {
        site = target;
        task = new TaskScope(target);
        pendingInitial = target;
        webLogin = null;
        if (authExpired()) {
            transactions.clear();
            auth = null;
        }
    }

    private boolean replaysAuthResult(WebAddress target) {
        return transactions.stream().anyMatch(transaction -> sameEndpoint(transaction.callback, target.uri()))
                && hasAuthResult(target.uri());
    }

    /** Called after the initial redirect chain reaches its first document. */
    public void pageLoaded(String url) {
        WebAddress finalAddress = WebAddress.parse(url);
        if (settled) {
            if (task.allows(finalAddress) && finalAddress.scheme().equals("https")) {
                site = finalAddress;
                task = new TaskScope(finalAddress);
            }
            return;
        }
        if (finalAddress != null && pendingInitial.sameOrigin(finalAddress)) {
            // A sign-in page is a temporary step, not the task's new home.
            if (webLogin == null) {
                site = finalAddress;
                task = new TaskScope(finalAddress);
            }
            settled = true;
        }
    }

    public Decision evaluateFrame(String url) {
        WebAddress target = WebAddress.parse(url);
        if (target == null) return Decision.BLOCK_INVALID;
        if (isCommunity(target)) return Decision.BLOCK_COMMUNITY;
        return isSearch(target) && !isCaptcha(target) ? Decision.BLOCK_SEARCH : Decision.ALLOW;
    }

    private boolean registerAuthorization(WebAddress target, boolean initial) {
        Map<String, String> query = target.parameters();
        String redirectUri = query.get("redirect_uri");
        String client = query.get("client_id");
        if (!target.scheme().equals("https") || redirectUri == null || client == null || client.isBlank()
                || (!query.containsKey("response_type") && !query.containsKey("code_challenge"))
                || !isAuthenticationPage(target)) return false;
        URI callback;
        try {
            callback = URI.create(redirectUri);
            if (!callback.isAbsolute() || callback.isOpaque() || callback.getRawFragment() != null || callback.getScheme() == null
                    || UNSAFE_CALLBACK_SCHEMES.contains(callback.getScheme().toLowerCase(Locale.ROOT))
                    || callback.getRawUserInfo() != null || redirectUri.contains("\\")) return false;
        } catch (IllegalArgumentException ignored) { return false; }
        if (!initial) {
            WebAddress webCallback = WebAddress.parse(redirectUri);
            boolean returnsToSite = withinTask(webCallback);
            boolean returnsToTransaction = transactions.stream().anyMatch(transaction -> sameEndpoint(transaction.callback, callback));
            if (!returnsToSite && !returnsToTransaction) return false;
        }
        if (auth == null || !sameEndpoint(auth.callback, callback)) {
            if (transactions.size() >= 4) return false;
            auth = new AuthTransaction(callback, query.get("state"), clock.getAsLong());
            transactions.add(auth);
        }
        // A nested authorization request with a different state is not allowed to replace the outer state.
        if (auth.origins.size() >= MAX_AUTH_ORIGINS && !auth.origins.contains(origin(target))) return false;
        auth.origins.add(origin(target));
        return true;
    }

    private AuthTransaction callbackTransaction(String url) {
        if (auth == null) return null;
        try {
            URI target = URI.create(url);
            Map<String, String> parameters = callbackParameters(target);
            if (parameters == null || !hasAuthResult(target)) return null;
            for (int i = transactions.size() - 1; i >= 0; i--) {
                AuthTransaction transaction = transactions.get(i);
                if (!sameEndpoint(transaction.callback, target)) continue;
                if (transaction.state != null && !transaction.state.equals(parameters.get("state"))) continue;
                Map<String, String> required = WebAddress.decodeParameters(transaction.callback.getRawQuery());
                if (parameters.entrySet().containsAll(required.entrySet())) return transaction;
            }
            return null;
        } catch (IllegalArgumentException ignored) { return null; }
    }

    private static Map<String, String> callbackParameters(URI target) {
        Map<String, String> query = WebAddress.decodeParameters(target.getRawQuery());
        Map<String, String> fragment = WebAddress.decodeParameters(target.getRawFragment());
        if (target.getRawQuery() != null && !target.getRawQuery().isEmpty() && query.isEmpty()) return null;
        if (target.getRawFragment() != null && !target.getRawFragment().isEmpty() && fragment.isEmpty()) return null;
        Map<String, String> combined = new java.util.LinkedHashMap<>(query);
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

    private void completeTransaction(AuthTransaction transaction) {
        int index = transactions.indexOf(transaction);
        if (index >= 0) transactions.subList(index, transactions.size()).clear();
        auth = transactions.isEmpty() ? null : transactions.get(transactions.size() - 1);
    }

    private static boolean sameEndpoint(URI expected, URI actual) {
        if (expected.getScheme() == null || actual.getScheme() == null
                || !expected.getScheme().equalsIgnoreCase(actual.getScheme())) return false;
        WebAddress a = WebAddress.parse(expected.toString());
        WebAddress b = WebAddress.parse(actual.toString());
        if (a != null || b != null) return a != null && a.sameOrigin(b) && a.path().equals(b.path());
        return java.util.Objects.equals(expected.getAuthority(), actual.getAuthority())
                && java.util.Objects.equals(expected.getPath(), actual.getPath());
    }

    private boolean authExpired() { return auth != null && expired(auth); }
    private boolean webLoginExpired() { return clock.getAsLong() - webLogin.startedAt > AUTH_TIMEOUT_MS; }
    private boolean expired(AuthTransaction transaction) { return clock.getAsLong() - transaction.startedAt > AUTH_TIMEOUT_MS; }
    private static String origin(WebAddress value) { return value.scheme() + "://" + value.host() + ":" + value.port(); }

    private boolean withinTask(WebAddress target) {
        return task.allows(target);
    }

    private static boolean isGoogleSignIn(WebAddress target) {
        if (!target.scheme().equals("https") || target.port() != 443
                || !Set.of("accounts.google.com", "accounts.google.co.kr").contains(target.host())) return false;
        String path = target.normalizedPath();
        return path.matches("/(?:ServiceLogin(?:Auth)?|AccountChooser|Login|InteractiveLogin|CheckCookie|CheckCookieError)/?")
                || path.startsWith("/signin/") || path.startsWith("/v3/signin/") || path.startsWith("/v2/signin/")
                || path.equals("/v2/identifier");
    }

    private static WebAddress googleSignInReturn(WebAddress target) {
        if (!isGoogleSignIn(target)) return null;
        return WebAddress.parse(resolveLink(target.parameters().get("continue")));
    }

    private boolean googleSignInStepAllowed(WebAddress target) {
        if (!isGoogleSignIn(target) || !webLogin.provider.sameOrigin(target)) return false;
        Map<String, String> query = target.parameters();
        if (target.uri().getRawQuery() != null && !target.uri().getRawQuery().isEmpty() && query.isEmpty()) return false;
        WebAddress returning = googleSignInReturn(target);
        return !query.containsKey("continue") || (returning != null && webLogin.returning.allows(returning));
    }

    private static boolean validWebReturn(WebAddress target) {
        return target.scheme().equals("https") && !isSearch(target) && !isCommunity(target)
                && !Set.of("accounts.google.com", "accounts.google.co.kr").contains(target.host());
    }

    private static boolean isAuthenticationPage(WebAddress url) {
        String path = url.normalizedPath().toLowerCase(Locale.ROOT);
        if (IDENTITY_HOSTS.contains(url.host()) && !url.host().equals("github.com")
                && !url.host().endsWith("facebook.com") && !url.host().equals("twitter.com")
                && !url.host().equals("x.com")) return true;
        return path.matches(".*(?:^|/)(?:oauth2?|authorize|authorization|auth|login|signin|sign-in|sso|saml2?|account|accounts|consent|challenge)(?:/.*|\\.[a-z]+)?$");
    }

    private static boolean isCaptcha(WebAddress url) {
        String host = url.host();
        return ((host.equals("www.google.com") || host.equals("www.recaptcha.net") || host.equals("recaptcha.net"))
                    && url.normalizedPath().startsWith("/recaptcha/"))
                || host.equals("challenges.cloudflare.com")
                || host.equals("hcaptcha.com") || host.endsWith(".hcaptcha.com");
    }

    public static boolean isSearch(WebAddress url) {
        return WebLinks.isDiscovery(url);
    }

    public static boolean isCommunity(WebAddress url) {
        return WebLinks.isCommunity(url);
    }

    private static final class WebLogin {
        final WebAddress provider;
        final TaskScope returning;
        final long startedAt;
        WebLogin(WebAddress provider, WebAddress returning, long startedAt) {
            this.provider = provider;
            this.returning = new TaskScope(returning);
            this.startedAt = startedAt;
        }
    }

    private static final class AuthTransaction {
        final URI callback;
        final String state;
        final long startedAt;
        final Set<String> origins = new HashSet<>();
        AuthTransaction(URI callback, String state, long startedAt) {
            this.callback = callback; this.state = state; this.startedAt = startedAt;
        }
    }
}
