package dev.air.navigation;

import static dev.air.navigation.NavigationPolicy.Decision.*;
import static org.junit.Assert.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public final class SearchAndCommunityTest {
    private static final String SAFE = "https://www.google.com/search?q=night+pharmacy&udm=14&safe=active";
    private NavigationPolicy loaded(String url) {
        var policy = new NavigationPolicy(url);
        policy.pageLoaded(url);
        return policy;
    }
    private String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private String wrapper(String destination) { return "https://www.google.com/url?q=" + encode(destination); }

    @Test public void typedTermsOpenSafeSearchTextResults() {
        assertEquals(SAFE, NavigationPolicy.searchUrl("  night pharmacy "));
        assertEquals("https://www.google.com/search?q=%EC%95%BD%EA%B5%AD&udm=14&safe=active", NavigationPolicy.searchUrl("약국"));
        assertNull(NavigationPolicy.searchUrl("   "));
        assertNull(NavigationPolicy.searchUrl(null));
        assertEquals(SAFE, NavigationPolicy.resolveLink(SAFE));
        assertEquals(ALLOW, NavigationPolicy.entryDecision(SAFE));
        assertEquals("www.google.com", new NavigationPolicy(SAFE).lockedHost());
    }

    @Test public void everyGoogleSearchCollapsesIntoSafeTextResults() {
        for (String url : new String[] {"https://www.google.com/search?q=night+pharmacy",
                "https://google.co.kr/search?q=night+pharmacy&tbm=isch", "https://www.google.com/search?q=night+pharmacy&udm=2",
                "https://www.google.com/search?q=night+pharmacy&udm=14&safe=off", "https://www.google.com/search?udm=50&q=night%20pharmacy",
                "https://www.google.com/search?q=night+pharmacy&udm=14&safe=active&tbm=vid", wrapper("https://www.google.com/search?q=night+pharmacy")}) {
            assertEquals(url, SAFE, NavigationPolicy.resolveLink(url));
            assertEquals(url, ALLOW, NavigationPolicy.entryDecision(url));
            assertEquals(url, ALLOW, loaded("https://example.com/").evaluate(url, false, true));
        }
        assertEquals(SAFE + "&start=10", NavigationPolicy.resolveLink("https://www.google.com/search?q=night+pharmacy&start=10&sca_esv=x"));
        assertEquals(SAFE, NavigationPolicy.resolveLink("https://www.google.com/search?q=night+pharmacy&start=-1"));
    }

    @Test public void safeResultsKeepGoogleOwnParametersAndPagination() {
        String paged = SAFE + "&start=20&sca_esv=abc&ei=xyz";
        assertEquals(paged, NavigationPolicy.resolveLink(paged));
        assertEquals(ALLOW, NavigationPolicy.entryDecision(paged));
        var policy = loaded(SAFE);
        assertEquals(ALLOW, policy.evaluate("https://pharmacy.example/hours", false, true));
        assertEquals("pharmacy.example", policy.lockedHost());
        assertEquals(ALLOW, policy.evaluate(paged, false, true));
    }

    @Test public void unsafeAmbiguousAndOtherSearchStaysBlocked() {
        for (String url : new String[] {"https://www.google.com/search", "https://www.google.com/search?q=",
                "https://www.google.com/search?q=a&q=b", "http://www.google.com/search?q=a",
                "https://www.google.com:8443/search?q=a", "https://www.google.com/", "https://www.google.com/imghp",
                "https://www.google.com/search/about?q=a", "https://www.google.de/search?q=a",
                "https://www.bing.com/search?q=a", "https://search.naver.com/search.naver?query=a",
                "https://duckduckgo.com/?q=a", "https://youtube.com/results?search_query=a"}) {
            assertEquals(url, BLOCK_SEARCH, NavigationPolicy.entryDecision(url));
            assertEquals(url, BLOCK_SEARCH, loaded("https://example.com/").evaluate(url, false, true));
        }
        // Frames are not rewritten, so only results that are already safe may be embedded.
        assertEquals(BLOCK_SEARCH, loaded("https://example.com/").evaluateFrame("https://www.google.com/search?q=a&udm=2"));
        assertEquals(ALLOW, loaded("https://example.com/").evaluateFrame(SAFE));
    }

    @Test public void communitiesNeverOpenByAnyRoute() {
        for (String url : new String[] {"https://www.dcinside.com/", "https://gall.dcinside.com/board/lists?id=x",
                "https://m.fmkorea.com/best", "https://theqoo.net/hot", "https://www.reddit.com/r/all", "https://old.reddit.com/",
                "https://mlbpark.donga.com/mp/b.php", "https://pann.nate.com/talk/1", "http://ruliweb.com/", "https://ARCA.LIVE./b/x"}) {
            assertEquals(url, BLOCK_COMMUNITY, NavigationPolicy.entryDecision(url));
            var policy = loaded("https://example.com/");
            assertEquals(url, BLOCK_COMMUNITY, policy.evaluate(url, false, true));
            assertEquals(url, BLOCK_COMMUNITY, policy.evaluate(url, true, false));
            assertEquals(url, BLOCK_COMMUNITY, policy.evaluateFrame(url));
            assertEquals(url, "example.com", policy.lockedHost());
        }
        assertEquals(BLOCK_COMMUNITY, NavigationPolicy.entryDecision(wrapper("https://gall.dcinside.com/")));
        assertEquals(BLOCK_COMMUNITY, loaded(SAFE).evaluate(wrapper("https://www.fmkorea.com/"), false, true));
        assertThrows(IllegalArgumentException.class, () -> new NavigationPolicy("https://reddit.com/"));
    }

    @Test public void lookalikesAndParentSitesAreNotCommunities() {
        for (String url : new String[] {"https://notdcinside.com/", "https://dcinside.com.example/",
                "https://www.donga.com/news", "https://www.nate.com/", "https://redditstatus.com/"}) {
            assertEquals(url, ALLOW, NavigationPolicy.entryDecision(url));
        }
    }

    @Test public void googleSignInCannotReturnToACommunity() {
        var policy = new NavigationPolicy("https://accounts.google.com/AccountChooser?continue="
                + encode("https://www.reddit.com/") + "&service=wise");
        assertFalse(policy.isAuthenticating());
        assertEquals("accounts.google.com", policy.lockedHost());
        assertEquals(BLOCK_COMMUNITY, policy.evaluate("https://www.reddit.com/", true, false));
    }
}
