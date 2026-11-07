package dev.air.navigation;

import static dev.air.navigation.NavigationPolicy.Decision.*;
import static org.junit.Assert.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public final class NavigationPolicyTest {
    private static final String FORM = "https://docs.google.com/forms/d/e/form_A/viewform?usp=send_form";

    private String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private String wrapper(String destination) { return "https://www.google.com/url?q=" + encode(destination); }
    private NavigationPolicy site(String url) {
        NavigationPolicy policy = new NavigationPolicy(url);
        policy.pageLoaded();
        return policy;
    }
    private String oauth(String host, String callback, String state) {
        return "https://" + host + "/oauth2/authorize?client_id=example&response_type=code&redirect_uri="
                + encode(callback) + "&state=" + state;
    }

    @Test public void everyOrdinaryPageOpensWhateverLedToIt() {
        NavigationPolicy policy = site(FORM);
        for (String url : new String[] {"https://shop.example/orders", "https://other.example/", "http://shop.example/",
                "https://docs.google.com/forms/", "https://docs.google.com/document/u/0/", "https://drive.google.com/drive/my-drive",
                "https://github.com/search?q=x", "https://github.com/explore", "https://www.google.com/", "https://google.co.kr/",
                "https://www.google.com/webhp", "https://www.google.com/recaptcha/api2/anchor", "https://www.naver.com/",
                "https://m.naver.com/", "https://www.daum.net/", "https://accounts.google.com/AccountChooser?continue=https://x.example/"}) {
            assertEquals(url, ALLOW, NavigationPolicy.entryDecision(url));
            assertEquals(url, ALLOW, policy.evaluate(url));
            assertEquals(url, ALLOW, NavigationPolicy.evaluateFrame(url));
        }
    }

    @Test public void addressesWithCharactersBrowsersAcceptStillOpen() {
        for (String url : new String[] {"https://shop.example/list?ids=1|2", "https://shop.example/a[1]/b", "https://shop.example/?q={x}",
                "https://shop.example/?q=a^b", "https://shop.example/#/route|x", "https://shop.example/100%/off", "https://shop.example/?a=50%",
                "https://shop.example/?u=a\\b", "https://shop.example/?a=\"b\"", "https://shop.example/#a#b", "https://my_site.tistory.com/1",
                "https://shop.example/?e=%EC%95%BD%EA%B5%AD", "https://shop.example/?e=약국"}) {
            assertEquals(url, ALLOW, NavigationPolicy.entryDecision(url));
            assertEquals(url, ALLOW, site("https://example.com/").evaluate(url));
        }
    }

    @Test public void readingAnUnusualAddressDoesNotHideWhereItGoes() {
        assertEquals("nimi.wiki", WebAddress.parse("https://nimi.wiki/?a=1|2").host());
        assertEquals(BLOCK_ADULT, NavigationPolicy.entryDecision("https://nimi.wiki/?a=1|2"));
        assertEquals(BLOCK_ADULT, NavigationPolicy.entryDecision("https://my_mirror.nimi.wiki./x|y"));
        assertEquals(BLOCK_COMMUNITY, NavigationPolicy.entryDecision("https://www.reddit.com/r/a|b"));
        assertEquals(BLOCK_SEARCH, NavigationPolicy.entryDecision("https://m.youtube.com/shorts/x|y"));
        assertEquals(BLOCK_SEARCH, NavigationPolicy.entryDecision("https://www.bing.com/search?q=a|b"));
        assertEquals("night|pharmacy", WebAddress.parse("https://shop.example/?q=night|pharmacy").parameters().get("q"));
    }

    @Test public void malformedAndLocalSchemeEntriesAreRejected() {
        for (String url : new String[] {"javascript:alert(1)", "file:///etc/hosts", "content://private",
                "intent://evil", "data:text/html,test", "about:config", "tel:0212345678", "https://x.test\\@evil.test/",
                "https://x.test/a\\b", "https://example.com:70000/", "https://user:pass@example.com/", "https://shop.example@evil.test/",
                "https://example.com/\n", "https:///no-host", "https:no-host", "https://exa mple.com/", "https://exa*mple.com/"}) {
            assertEquals(url, BLOCK_INVALID, NavigationPolicy.entryDecision(url));
        }
    }

    @Test public void idnAndTrailingDotHaveStableIdentity() {
        assertEquals(WebAddress.parse("https://한국.kr/").host(), WebAddress.parse("https://xn--3e0b707e.kr/").host());
        assertEquals("example.com", WebAddress.parse("https://EXAMPLE.COM./path").host());
    }

    @Test public void searchEnginesAndImageSearchStayBlocked() {
        for (String url : new String[] {"https://www.google.com/imghp", "https://www.bing.com/", "https://duckduckgo.com/",
                "https://search.naver.com/search.naver?query=hi", "https://search.daum.net/", "https://search.brave.com/"}) {
            assertEquals(url, BLOCK_SEARCH, NavigationPolicy.entryDecision(url));
            assertEquals(url, BLOCK_SEARCH, site(FORM).evaluate(url));
        }
    }

    @Test public void pageContentWithoutAWebAddressRunsInTheEngine() {
        NavigationPolicy policy = site("https://example.com/");
        for (String url : new String[] {"about:blank", "about:srcdoc", "blob:https://example.com/1-2", "data:text/html,hi", "javascript:void(0)"}) {
            assertEquals(url, ALLOW, policy.evaluate(url));
            assertEquals(url, ALLOW, NavigationPolicy.evaluateFrame(url));
        }
        assertEquals(BLOCK_INVALID, policy.evaluate("about:config"));
        assertEquals(BLOCK_INVALID, NavigationPolicy.evaluateFrame("about:config"));
    }

    @Test public void otherAppsLinksGoToThoseApps() {
        NavigationPolicy policy = site("https://example.com/");
        for (String url : new String[] {"tel:0212345678", "mailto:a@example.com", "sms:01012345678", "market://details?id=x",
                "kakaotalk://open", "ispmobile://pay?x=1", "shinhan-sr-ansimclick://pay"}) {
            assertEquals(url, OPEN_APP, policy.evaluate(url));
            assertEquals(url, BLOCK_INVALID, NavigationPolicy.evaluateFrame(url));
        }
    }

    @Test public void localAndEngineSchemesNeverGoToAnotherApp() {
        NavigationPolicy policy = site("https://example.com/");
        for (String url : new String[] {"file:///etc/hosts", "content://private/x", "intent://evil", "chrome://browser/x",
                "resource://android/x", "view-source:https://example.com/", "moz-extension://x/y", ":nothing", "not a link"}) {
            assertEquals(url, BLOCK_INVALID, policy.evaluate(url));
        }
    }

    @Test public void googleLinkWrappersResolveToTheirCheckedDestination() {
        assertEquals(FORM, NavigationPolicy.resolveLink(wrapper(FORM)));
        assertEquals(ALLOW, NavigationPolicy.entryDecision(wrapper(wrapper(FORM))));
        assertEquals(BLOCK_SEARCH, NavigationPolicy.entryDecision(wrapper("https://www.bing.com/search?q=test")));
        assertEquals(ALLOW, site(FORM).evaluate(wrapper(FORM)));
        assertEquals(BLOCK_COMMUNITY, site(FORM).evaluate(wrapper("https://www.reddit.com/")));
    }

    @Test public void googlesOwnLinkFormsResolve() {
        String encoded = encode("https://shop.example/a?b=1");
        assertEquals("https://shop.example/a?b=1", NavigationPolicy.resolveLink(
                "https://www.google.com/url?sa=t&rct=j&q=&esrc=s&source=web&url=" + encoded + "&usg=x"));
        assertEquals("https://shop.example/a?b=1", NavigationPolicy.resolveLink(
                "https://www.google.com/url?q=night+pharmacy&url=" + encoded));
        assertEquals("https://shop.example/a?b=1", NavigationPolicy.resolveLink(
                "https://www.google.com/url?q=" + encoded + "&url=" + encoded));
        assertEquals("https://shop.example/a?b=1", NavigationPolicy.resolveLink(
                "https://www.google.com/url?q=" + encoded + "&sa=U&ved=abc&usg=def"));
    }

    @Test public void malformedOrAmbiguousWrappersCannotChooseAnotherDestination() {
        String other = "https://docs.google.com/forms/d/e/form_B/viewform";
        for (String url : new String[] {
                "https://www.google.com/url", wrapper("javascript:alert(1)"), wrapper("intent://other"),
                wrapper(FORM) + "&q=" + encode(other), wrapper(FORM) + "&url=" + encode(other),
                "https://www.google.com:444/url?q=" + encode(FORM), "http://www.google.com/url?q=" + encode(FORM)}) {
            assertEquals(url, BLOCK_INVALID, NavigationPolicy.entryDecision(url));
        }
        String nested = FORM;
        for (int i = 0; i < 5; i++) nested = wrapper(nested);
        assertEquals(BLOCK_INVALID, NavigationPolicy.entryDecision(nested));
        String lookalike = "https://www.google.com.evil.test/url?q=" + encode(FORM);
        assertEquals(lookalike, NavigationPolicy.resolveLink(lookalike));
    }

    @Test public void oauthResultReturnsToTheAppThatOpenedAir() {
        NavigationPolicy policy = site(oauth("identity.example", "myapp://login/callback", "original"));
        assertEquals(AUTH_CALLBACK, policy.evaluate("myapp://login/callback?code=abc&state=original"));
    }

    @Test public void aRedirectBeforeTheFirstPageCanCarryTheAppsSignIn() {
        NavigationPolicy policy = new NavigationPolicy("https://short.example/login");
        assertEquals(ALLOW, policy.evaluate(oauth("identity.example", "myapp:/return", "a")));
        policy.pageLoaded();
        assertEquals(AUTH_CALLBACK, policy.evaluate("myapp:/return?code=x&state=a"));
    }

    @Test public void aPagesOwnSignInIsAnOrdinaryPageMove() {
        NavigationPolicy policy = site("https://shop.example/");
        assertEquals(ALLOW, policy.evaluate(oauth("identity.example", "https://shop.example/callback", "a")));
        assertEquals(ALLOW, policy.evaluate("https://identity.example/login"));
        assertEquals(ALLOW, policy.evaluate("https://shop.example/callback?code=x&state=a"));
        assertEquals(ALLOW, policy.evaluate("https://shop.example/callback?code=x&state=a"));
    }

    @Test public void onlyTheExpectedResultCountsAsTheAppsReturn() {
        NavigationPolicy policy = site(oauth("identity.example", "myapp://return?flow=123", "expected"));
        for (String url : new String[] {"myapp://other?code=a&state=expected", "myapp://return?code=a&flow=123",
                "myapp://return?code=a&flow=123&state=wrong", "myapp://return?code=a&flow=123&state=expected&state=wrong",
                "myapp://return?code=a&flow=wrong&state=expected"}) {
            assertEquals(url, OPEN_APP, policy.evaluate(url));
        }
        assertEquals(AUTH_CALLBACK, policy.evaluate("myapp://return?code=a&flow=123&state=expected"));
    }

    @Test public void fragmentTokenAndErrorResultsWork() {
        NavigationPolicy policy = site(oauth("identity.example", "myapp:/return?flow=123", "a"));
        assertEquals(AUTH_CALLBACK, policy.evaluate("myapp:/return?flow=123#access_token=token&state=a"));
        assertEquals(AUTH_CALLBACK, policy.evaluate("myapp:/return?flow=123&error=access_denied&state=a"));
        assertEquals(OPEN_APP, policy.evaluate("myapp:/return?flow=123&state=a#access_token=token&state=wrong"));
    }

    @Test public void anAuthorizationCannotUseUnsafeCallbackSchemes() {
        for (String callback : new String[] {"javascript:alert(1)", "file:///etc/hosts", "content://secret", "intent://evil", "myapp:opaque"}) {
            NavigationPolicy policy = site(oauth("identity.example", callback, "a"));
            assertNotEquals(callback, AUTH_CALLBACK, policy.evaluate(callback + "?code=x&state=a"));
        }
    }

    @Test public void httpsResultWithoutAVerifiedAppOpensHereOnce() {
        String callback = "https://app.example/oauth/callback";
        NavigationPolicy policy = site(oauth("identity.example", callback, "a"));
        String returned = callback + "?code=abc&state=a";
        assertEquals(AUTH_CALLBACK, policy.evaluate(returned));
        policy.signInReturned();
        assertEquals(ALLOW, policy.evaluate(returned));
    }

    @Test public void federatedSignInKeepsTheAppsReturnAndState() {
        NavigationPolicy policy = site(oauth("identity.example", "myapp:/return", "outer"));
        assertEquals(ALLOW, policy.evaluate(oauth("accounts.google.com", "https://identity.example/callback", "inner")));
        assertEquals(ALLOW, policy.evaluate("https://identity.example/callback?code=google-code&state=inner"));
        assertEquals(OPEN_APP, policy.evaluate("myapp:/return?code=app-code&state=inner"));
        assertEquals(AUTH_CALLBACK, policy.evaluate("myapp:/return?code=app-code&state=outer"));
    }

    @Test public void pathTraversalCannotReachABlockedRoute() {
        assertEquals(BLOCK_SEARCH, NavigationPolicy.entryDecision("https://m.youtube.com/watch/../shorts/x"));
        assertEquals(BLOCK_SEARCH, NavigationPolicy.entryDecision("https://m.youtube.com/watch/%2e%2e/shorts/x"));
        assertEquals(BLOCK_SEARCH, NavigationPolicy.evaluateFrame("https://www.google.com/recaptcha/../search?q=hi"));
        assertEquals(BLOCK_SEARCH, NavigationPolicy.evaluateFrame("https://www.google.com/recaptcha/%2e%2e/search?q=hi"));
    }

    @Test public void encodedNativeCallbackPathsMatchTheAndroidDecodedEndpoint() {
        NavigationPolicy policy = site(oauth("identity.example", "msauth://example.client/a%2Bb%3D", "a"));
        assertEquals(AUTH_CALLBACK, policy.evaluate("msauth://example.client/a+b=?code=x&state=a"));
        assertEquals(OPEN_APP, policy.evaluate("msauth://example.client/other?code=x&state=a"));
    }
}
