package dev.air.navigation;

import static dev.air.navigation.NavigationPolicy.Decision.*;
import static org.junit.Assert.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

public final class NavigationPolicyTest {
    private NavigationPolicy site(String url) {
        NavigationPolicy policy = new NavigationPolicy(url);
        policy.pageLoaded(url);
        return policy;
    }
    private String oauth(String host, String callback, String state) {
        return "https://" + host + "/oauth2/authorize?client_id=example&response_type=code&redirect_uri="
                + URLEncoder.encode(callback, StandardCharsets.UTF_8) + "&state=" + state;
    }

    @Test public void sameSiteFormsPathsAndFragmentsRemainUsable() {
        NavigationPolicy policy = site("https://shop.example/orders");
        assertEquals(ALLOW, policy.evaluate("https://shop.example/account?tab=profile#name", false, true));
        assertEquals(ALLOW, policy.evaluate("https://www.shop.example/checkout", false, true));
    }

    @Test public void otherSitesOpenAndBecomeTheTask() {
        for (String url : new String[] {"https://other.example/", "https://shop.example.evil.test/",
                "https://evil-shop.example/", "http://shop.example/"}) {
            NavigationPolicy policy = site("https://shop.example/");
            assertEquals(url, ALLOW, policy.evaluate(url, false, true));
            assertEquals(url, WebAddress.parse(url).host(), policy.lockedHost());
        }
        assertEquals(BLOCK_INVALID, site("https://shop.example/").evaluate("https://shop.example@evil.test/", false, true));
    }

    @Test public void malformedAndLocalSchemeEntriesAreRejected() {
        for (String url : new String[] {"javascript:alert(1)", "file:///etc/hosts", "content://private",
                "intent://evil", "data:text/html,test", "about:config", "https://x.test\\@evil.test/",
                "https://example.com:70000/", "https://user:pass@example.com/", "https://example.com/\n"}) {
            assertEquals(url, BLOCK_INVALID, NavigationPolicy.entryDecision(url));
        }
    }

    @Test public void idnAndTrailingDotHaveStableIdentity() {
        assertEquals(WebAddress.parse("https://한국.kr/").host(), WebAddress.parse("https://xn--3e0b707e.kr/").host());
        assertEquals(ALLOW, site("https://example.com/").evaluate("https://EXAMPLE.COM./path", false, true));
    }

    @Test public void knownSearchPortalsAreBlockedEvenAtEntry() {
        for (String url : new String[] {"https://www.google.com/imghp", "https://google.co.kr/",
                "https://www.bing.com/", "https://duckduckgo.com/", "https://search.naver.com/search.naver?query=hi",
                "https://m.naver.com/", "https://search.daum.net/", "https://search.brave.com/"}) {
            assertEquals(url, BLOCK_SEARCH, NavigationPolicy.entryDecision(url));
        }
        assertEquals(ALLOW, NavigationPolicy.entryDecision("https://accounts.google.com/signin"));
        assertEquals(ALLOW, NavigationPolicy.entryDecision("https://nid.naver.com/nidlogin.login"));
    }

    @Test public void initialServerRedirectsResolveThenLockToDestination() {
        NavigationPolicy policy = new NavigationPolicy("https://short.example/x");
        assertEquals(ALLOW, policy.evaluate("https://menu.restaurant.example/", true, false));
        policy.pageLoaded("https://menu.restaurant.example/");
        assertEquals("menu.restaurant.example", policy.lockedHost());
        assertEquals(ALLOW, policy.evaluate("https://menu.restaurant.example/order", false, true));
        assertEquals(ALLOW, policy.evaluate("https://short.example/another-link", false, true));
        assertEquals(ALLOW, policy.evaluate("https://different.example/", true, false));
    }

    @Test public void firstLoadAllowsScriptAndClickedCrossSiteMoves() {
        NavigationPolicy policy = new NavigationPolicy("https://example.com/");
        assertEquals(ALLOW, policy.evaluate("https://other.test/", false, false));
        assertEquals(ALLOW, policy.evaluate("https://another.test/", false, true));
    }

    @Test public void redirectChainsNeverAllowSearch() {
        NavigationPolicy policy = new NavigationPolicy("https://short.example/");
        assertEquals(BLOCK_SEARCH, policy.evaluate("https://google.com/", true, false));
        for (int i = 0; i < 12; i++) assertEquals(ALLOW, policy.evaluate("https://hop" + i + ".example/", true, false));
        assertEquals(BLOCK_SEARCH, policy.evaluate("https://www.bing.com/search?q=x", true, false));
    }

    @Test public void oauthCanReturnToExactAppCallback() {
        NavigationPolicy policy = site(oauth("identity.example", "myapp://login/callback", "original"));
        assertEquals(AUTH_CALLBACK, policy.evaluate("myapp://login/callback?code=abc&state=original", true, false));
        assertEquals(BLOCK_INVALID, policy.evaluate("myapp://login/other?code=abc&state=original", true, false));
        assertEquals(BLOCK_INVALID, policy.evaluate("myapp://other/callback?code=abc&state=original", true, false));
    }

    @Test public void callbackStateCannotBeOmittedDuplicatedOrChanged() {
        NavigationPolicy policy = site(oauth("identity.example", "myapp:/callback", "expected"));
        for (String query : new String[] {"code=a", "code=a&state=wrong", "code=a&state=expected&state=wrong"}) {
            assertEquals(BLOCK_INVALID, policy.evaluate("myapp:/callback?" + query, true, false));
        }
        assertEquals(AUTH_CALLBACK, policy.evaluate("myapp:/callback?code=a&state=expected", true, false));
    }

    @Test public void callbackFixedQueryParametersMustRemainIntact() {
        NavigationPolicy policy = site(oauth("identity.example", "myapp://return?flow=123", "a"));
        assertEquals(BLOCK_INVALID, policy.evaluate("myapp://return?code=x&state=a&flow=wrong", true, false));
        assertEquals(AUTH_CALLBACK, policy.evaluate("myapp://return?code=x&state=a&flow=123", true, false));
    }

    @Test public void fragmentTokenAndErrorCallbacksWork() {
        NavigationPolicy policy = site(oauth("identity.example", "myapp:/return", "a"));
        assertEquals(AUTH_CALLBACK, policy.evaluate("myapp:/return#access_token=token&state=a", true, false));
        assertEquals(AUTH_CALLBACK, policy.evaluate("myapp:/return?error=access_denied&state=a", true, false));
    }

    @Test public void genericWebsiteOAuthMayUseAnyProviderWithScopedReturn() {
        NavigationPolicy policy = site("https://shop.example/");
        assertEquals(ALLOW, policy.evaluate(oauth("corporate.identity.example", "https://shop.example/callback", "a"), false, true));
        assertTrue(policy.isAuthenticating());
        assertEquals(ALLOW, policy.evaluate("https://corporate.identity.example/login", false, false));
        assertEquals(ALLOW, policy.evaluate("https://shop.example/callback?code=x&state=a", true, false));
        assertFalse(policy.isAuthenticating());
        assertEquals(ALLOW, policy.evaluate("https://corporate.identity.example/news", false, true));
    }

    @Test public void oauthCannotReplaceTheScopedReturnWithAnotherSiteOrApp() {
        NavigationPolicy policy = site("https://shop.example/");
        assertEquals(ALLOW, policy.evaluate(oauth("identity.example", "otherapp:/callback", "a"), false, true));
        assertFalse(policy.isAuthenticating());
        assertEquals(BLOCK_INVALID, policy.evaluate("otherapp:/callback?code=x&state=a", true, false));
    }

    @Test public void unscopedLoginPagesOpenWithoutStartingAuthentication() {
        NavigationPolicy policy = site("https://shop.example/");
        assertEquals(ALLOW, policy.evaluate("https://unknown.example/login", true, false));
        assertEquals(ALLOW, policy.evaluate("https://accounts.google.com/signin", true, false));
        assertFalse(policy.isAuthenticating());
    }

    @Test public void activeAuthMayFederateByServerRedirectButNotNormalLinks() {
        NavigationPolicy policy = site(oauth("identity.example", "myapp:/return", "a"));
        assertEquals(ALLOW, policy.evaluate("https://company.okta.com/sso/login", true, false));
        assertEquals(ALLOW, policy.evaluate("https://random.example/feed", false, true));
        assertEquals(BLOCK_SEARCH, policy.evaluate("https://www.bing.com/search?q=hi", true, false));
        assertEquals(AUTH_CALLBACK, policy.evaluate("myapp:/return?code=x&state=a", true, false));
    }

    @Test public void anAuthorizationCannotUseUnsafeCallbackSchemes() {
        for (String callback : new String[] {"javascript:alert(1)", "file:///etc/hosts", "content://secret", "intent://evil"}) {
            assertFalse(new NavigationPolicy(oauth("identity.example", callback, "a")).isAuthenticating());
        }
    }

    @Test public void authenticationExpiresWithoutBeingExtendedByAnotherRequest() {
        AtomicLong now = new AtomicLong(100);
        NavigationPolicy policy = new NavigationPolicy(oauth("identity.example", "myapp:/return", "a"), now::get);
        policy.pageLoaded(oauth("identity.example", "myapp:/return", "a"));
        now.addAndGet(16 * 60 * 1000);
        assertFalse(policy.isAuthenticating());
        assertEquals(BLOCK_EXPIRED, policy.evaluate("myapp:/return?code=x&state=a", true, false));
        assertEquals(ALLOW, policy.evaluate("https://company.okta.com/sso/login", true, false));
        assertEquals(BLOCK_INVALID, policy.evaluate("myapp:/return?code=x&state=a", true, false));
    }

    @Test public void captchaFramesWorkWithoutMakingGoogleSearchAccessible() {
        NavigationPolicy policy = site("https://shop.example/");
        assertEquals(ALLOW, policy.evaluateFrame("https://www.google.com/recaptcha/api2/anchor"));
        assertEquals(ALLOW, policy.evaluateFrame("https://challenges.cloudflare.com/turnstile/frame"));
        assertEquals(BLOCK_SEARCH, policy.evaluateFrame("https://www.google.com/search?q=hi"));
        assertEquals(ALLOW, policy.evaluateFrame("https://other.example/"));
        assertEquals(BLOCK_SEARCH, policy.evaluate("https://www.google.com/recaptcha/api2/anchor", false, true));
    }

    @Test public void httpsCallbackWithoutNativeHandlerCanBeConsumedOnce() {
        String callback = "https://app.example/oauth/callback";
        NavigationPolicy policy = site(oauth("identity.example", callback, "a"));
        String returned = callback + "?code=abc&state=a";
        assertEquals(AUTH_CALLBACK, policy.evaluate(returned, true, false));
        assertTrue(policy.completeWebCallback(returned));
        assertEquals("app.example", policy.lockedHost());
        assertFalse(policy.isAuthenticating());
        assertFalse(policy.completeWebCallback(returned));
    }

    @Test public void nestedFederatedOAuthPreservesTheOriginalAppReturnAndState() {
        NavigationPolicy policy = site(oauth("identity.example", "myapp:/return", "outer"));
        assertEquals(ALLOW, policy.evaluate(oauth("accounts.google.com", "https://identity.example/callback", "inner"), false, true));
        assertEquals(ALLOW, policy.evaluate("https://identity.example/callback?code=google-code&state=inner", true, false));
        assertTrue(policy.isAuthenticating());
        assertEquals(BLOCK_INVALID, policy.evaluate("myapp:/return?code=app-code&state=inner", true, false));
        assertEquals(AUTH_CALLBACK, policy.evaluate("myapp:/return?code=app-code&state=outer", true, false));
    }

    @Test public void sameSiteCallbackWithWrongStateIsStillBlocked() {
        NavigationPolicy policy = site("https://shop.example/");
        policy.evaluate(oauth("identity.example", "https://shop.example/callback", "expected"), false, true);
        assertEquals(BLOCK_SITE, policy.evaluate("https://shop.example/callback?code=x&state=wrong", true, false));
        assertEquals(BLOCK_SITE, policy.evaluate("https://shop.example/callback?code=x", true, false));
        assertTrue(policy.isAuthenticating());
    }

    @Test public void fragmentResultsKeepFixedQueryAndRejectConflictingState() {
        NavigationPolicy policy = site(oauth("identity.example", "myapp:/return?flow=123", "a"));
        assertEquals(AUTH_CALLBACK, policy.evaluate("myapp:/return?flow=123#access_token=token&state=a", true, false));
        assertEquals(BLOCK_INVALID, policy.evaluate("myapp:/return?flow=123&state=a#access_token=token&state=wrong", true, false));
    }

    @Test public void opaqueCallbackUrisDoNotCreateAnAuthenticationEscape() {
        NavigationPolicy policy = site(oauth("identity.example", "myapp:callback", "a"));
        assertFalse(policy.isAuthenticating());
        assertEquals(BLOCK_INVALID, policy.evaluate("myapp:anything#code=x&state=a", true, false));
    }

    @Test public void authenticationPathTraversalCannotOpenAProvidersFeed() {
        NavigationPolicy policy = site("https://shop.example/");
        policy.evaluate(oauth("identity.example", "https://shop.example/callback", "a"), false, true);
        assertEquals(BLOCK_SEARCH, policy.evaluate("https://github.com/login/../explore", true, false));
        assertEquals(BLOCK_SEARCH, policy.evaluate("https://github.com/login/%2e%2e/explore", true, false));
    }

    @Test public void captchaPathTraversalDoesNotOpenASearchFrame() {
        NavigationPolicy policy = site("https://shop.example/");
        assertEquals(BLOCK_SEARCH, policy.evaluateFrame("https://www.google.com/recaptcha/../search?q=hi"));
        assertEquals(BLOCK_SEARCH, policy.evaluateFrame("https://www.google.com/recaptcha/%2e%2e/search?q=hi"));
    }

    @Test public void encodedNativeCallbackPathsMatchTheAndroidDecodedEndpoint() {
        NavigationPolicy policy = site(oauth("identity.example", "msauth://example.client/a%2Bb%3D", "a"));
        assertEquals(AUTH_CALLBACK, policy.evaluate("msauth://example.client/a+b=?code=x&state=a", true, false));
        assertEquals(BLOCK_INVALID, policy.evaluate("msauth://example.client/other?code=x&state=a", true, false));
    }
}
