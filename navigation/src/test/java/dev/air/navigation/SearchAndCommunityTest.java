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
        policy.pageLoaded();
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
    }

    @Test public void everyGoogleSearchCollapsesIntoSafeTextResults() {
        for (String url : new String[] {"https://www.google.com/search?q=night+pharmacy",
                "https://google.co.kr/search?q=night+pharmacy&tbm=isch", "https://www.google.com/search?q=night+pharmacy&udm=2",
                "https://www.google.com/search?q=night+pharmacy&udm=14&safe=off", "https://www.google.com/search?udm=50&q=night%20pharmacy",
                "https://www.google.com/search?q=night+pharmacy&udm=14&safe=active&tbm=vid", wrapper("https://www.google.com/search?q=night+pharmacy")}) {
            assertEquals(url, SAFE, NavigationPolicy.resolveLink(url));
            assertEquals(url, ALLOW, NavigationPolicy.entryDecision(url));
            assertEquals(url, ALLOW, loaded("https://example.com/").evaluate(url));
        }
        assertEquals(SAFE + "&start=10", NavigationPolicy.resolveLink("https://www.google.com/search?q=night+pharmacy&start=10&sca_esv=x"));
        assertEquals(SAFE, NavigationPolicy.resolveLink("https://www.google.com/search?q=night+pharmacy&start=-1"));
    }

    @Test public void safeResultsKeepGoogleOwnParametersAndPagination() {
        String paged = SAFE + "&start=20&sca_esv=abc&ei=xyz";
        assertEquals(paged, NavigationPolicy.resolveLink(paged));
        assertEquals(ALLOW, NavigationPolicy.entryDecision(paged));
        var policy = loaded(SAFE);
        assertEquals(ALLOW, policy.evaluate("https://pharmacy.example/hours"));
        assertEquals(ALLOW, policy.evaluate(paged));
    }

    @Test public void unsafeAmbiguousAndOtherSearchStaysBlocked() {
        for (String url : new String[] {"https://www.google.com/search", "https://www.google.com/search?q=",
                "https://www.google.com/search?q=a&q=b", "http://www.google.com/search?q=a",
                "https://www.google.com:8443/search?q=a", "https://www.google.com/imghp",
                "https://www.google.com/search/about?q=a", "https://www.google.de/search?q=a",
                "https://www.bing.com/search?q=a", "https://search.naver.com/search.naver?query=a",
                "https://www.naver.com/search?query=a", "https://duckduckgo.com/?q=a"}) {
            assertEquals(url, BLOCK_SEARCH, NavigationPolicy.entryDecision(url));
            assertEquals(url, BLOCK_SEARCH, loaded("https://example.com/").evaluate(url));
        }
        // Frames are not rewritten, so only results that are already safe may be embedded.
        assertEquals(BLOCK_SEARCH, NavigationPolicy.evaluateFrame("https://www.google.com/search?q=a&udm=2"));
        assertEquals(ALLOW, NavigationPolicy.evaluateFrame(SAFE));
    }

    @Test public void youtubeOpensButItsFeedsAndShortsDoNot() {
        String results = "https://m.youtube.com/results?search_query=night+pharmacy";
        assertEquals(ALLOW, NavigationPolicy.entryDecision(results));
        assertEquals(ALLOW, NavigationPolicy.pageChange("https://m.youtube.com/watch?v=a", results));
        assertEquals(ALLOW, NavigationPolicy.entryDecision("https://youtube.com/watch?v=example"));
        // The home page opens, with its feed hidden by the built-in extension.
        for (String url : new String[] {"https://m.youtube.com/", "https://www.youtube.com/", "https://youtube.com/"}) {
            assertEquals(url, ALLOW, NavigationPolicy.entryDecision(url));
            assertEquals(url, ALLOW, NavigationPolicy.pageChange("https://m.youtube.com/watch?v=a", url));
            assertEquals(url, ALLOW, loaded(SAFE).evaluate(url));
        }
        for (String url : new String[] {"https://www.youtube.com/feed/subscriptions", "https://m.youtube.com/feed/subscriptions",
                "https://m.youtube.com/shorts/x"}) {
            assertEquals(url, BLOCK_SEARCH, NavigationPolicy.entryDecision(url));
            assertEquals(url, BLOCK_SEARCH, NavigationPolicy.pageChange("https://m.youtube.com/watch?v=a", url));
            assertEquals(url, BLOCK_SEARCH, loaded(SAFE).evaluate(url));
        }
    }

    @Test public void xFeedsStayBlockedButItsPostsOpen() {
        for (String url : new String[] {"https://x.com/", "https://x.com/home", "https://x.com/explore", "https://twitter.com/search?q=a"}) {
            assertEquals(url, BLOCK_SEARCH, NavigationPolicy.entryDecision(url));
            assertEquals(url, BLOCK_SEARCH, loaded(SAFE).evaluate(url));
        }
        assertEquals(ALLOW, NavigationPolicy.entryDecision("https://x.com/AirApp/status/1"));
    }

    @Test public void videosOpenFromSearchButNotFromAnotherVideo() {
        String watching = "https://m.youtube.com/watch?v=a";
        assertEquals(ALLOW, NavigationPolicy.pageChange("https://m.youtube.com/results?search_query=x", watching));
        assertEquals(ALLOW, NavigationPolicy.pageChange(SAFE, "https://www.youtube.com/watch?v=a"));
        assertEquals(ALLOW, NavigationPolicy.pageChange(null, watching));
        assertEquals(ALLOW, NavigationPolicy.pageChange(watching, watching + "&t=30s"));
        assertEquals(ALLOW, NavigationPolicy.pageChange(watching, "https://m.youtube.com/@channel"));
        for (String next : new String[] {"https://m.youtube.com/watch?v=b", "https://www.youtube.com/watch?v=b&pp=x",
                "https://youtu.be/b"}) {
            assertEquals(next, BLOCK_RECOMMENDATION, NavigationPolicy.pageChange(watching, next));
        }
        assertEquals(BLOCK_RECOMMENDATION, NavigationPolicy.pageChange("https://youtu.be/a", "https://m.youtube.com/watch?v=b"));
    }

    @Test public void aChosenPlaylistPlaysOnButGeneratedMixesDoNot() {
        assertEquals(ALLOW, NavigationPolicy.pageChange("https://m.youtube.com/watch?v=a&list=PL1",
                "https://m.youtube.com/watch?v=b&list=PL1&index=2"));
        assertEquals(BLOCK_RECOMMENDATION, NavigationPolicy.pageChange("https://m.youtube.com/watch?v=a&list=PL1",
                "https://m.youtube.com/watch?v=b&list=PL2"));
        assertEquals(BLOCK_RECOMMENDATION, NavigationPolicy.pageChange("https://m.youtube.com/watch?v=a",
                "https://m.youtube.com/watch?v=b&list=PL1"));
        assertEquals(BLOCK_RECOMMENDATION, NavigationPolicy.pageChange("https://m.youtube.com/watch?v=a&list=RDa",
                "https://m.youtube.com/watch?v=b&list=RDa"));
    }

    @Test public void inPageAddressChangesAreCheckedWithoutRewriting() {
        assertEquals(BLOCK_SEARCH, NavigationPolicy.pageChange(SAFE, "https://www.google.com/search?q=night+pharmacy&udm=2"));
        assertEquals(ALLOW, NavigationPolicy.pageChange(SAFE, SAFE + "&start=10"));
        assertEquals(BLOCK_COMMUNITY, NavigationPolicy.pageChange(SAFE, "https://www.reddit.com/"));
        assertEquals(ALLOW, NavigationPolicy.pageChange(SAFE, "about:blank"));
    }

    @Test public void communitiesNeverOpenByAnyRoute() {
        for (String url : new String[] {"https://www.dcinside.com/", "https://gall.dcinside.com/board/lists?id=x",
                "https://m.fmkorea.com/best", "https://theqoo.net/hot", "https://www.reddit.com/r/all", "https://old.reddit.com/",
                "https://mlbpark.donga.com/mp/b.php", "https://pann.nate.com/talk/1", "http://ruliweb.com/", "https://ARCA.LIVE./b/x"}) {
            assertEquals(url, BLOCK_COMMUNITY, NavigationPolicy.entryDecision(url));
            assertEquals(url, BLOCK_COMMUNITY, loaded("https://example.com/").evaluate(url));
            assertEquals(url, BLOCK_COMMUNITY, NavigationPolicy.evaluateFrame(url));
        }
        assertEquals(BLOCK_COMMUNITY, NavigationPolicy.entryDecision(wrapper("https://gall.dcinside.com/")));
        assertEquals(BLOCK_COMMUNITY, loaded(SAFE).evaluate(wrapper("https://www.fmkorea.com/")));
        assertThrows(IllegalArgumentException.class, () -> new NavigationPolicy("https://reddit.com/"));
    }

    @Test public void lookalikesAndParentSitesAreNotCommunities() {
        for (String url : new String[] {"https://notdcinside.com/", "https://dcinside.com.example/",
                "https://www.donga.com/news", "https://www.nate.com/", "https://redditstatus.com/"}) {
            assertEquals(url, ALLOW, NavigationPolicy.entryDecision(url));
        }
    }

    @Test public void adultSitesNeverOpenByAnyRoute() {
        for (String url : new String[] {"https://nimi.wiki/ko", "https://www.nimi.wiki/ko/popular", "https://twidouga.net/ko/",
                "https://twitter.net/", "http://www.twitter.net/home", "https://m.TWITTER.NET./i",
                "https://www.pornhub.com/", "https://xvideos2.com/", "https://ko.xhamster18.desi/", "https://hentai-site.example/",
                "https://yadong24.net/", "https://anything.xxx/", "https://shop.adult/", "https://pages.sex/"}) {
            assertEquals(url, BLOCK_ADULT, NavigationPolicy.entryDecision(url));
            assertEquals(url, BLOCK_ADULT, loaded("https://example.com/").evaluate(url));
            assertEquals(url, BLOCK_ADULT, NavigationPolicy.evaluateFrame(url));
            assertEquals(url, BLOCK_ADULT, NavigationPolicy.pageChange("https://example.com/", url));
        }
        assertEquals(BLOCK_ADULT, NavigationPolicy.entryDecision(wrapper("https://nimi.wiki/ko")));
        assertEquals(BLOCK_ADULT, loaded(SAFE).evaluate(wrapper("https://twitter.net/")));
        assertThrows(IllegalArgumentException.class, () -> new NavigationPolicy("https://twitter.net/"));
        var signIn = new NavigationPolicy("https://accounts.google.com/AccountChooser?continue="
                + encode("https://twitter.net/") + "&service=wise");
        assertEquals(BLOCK_ADULT, signIn.evaluate("https://twitter.net/"));
    }

    @Test public void xAndOrdinarySitesAreNotAdult() {
        for (String url : new String[] {"https://x.com/AirApp/status/1", "https://twitter.com/AirApp",
                "https://api.twitter.com/oauth/authorize?oauth_token=t", "https://www.sussex.ac.uk/", "https://www.essex.gov.uk/",
                "https://twitter.network.example/", "https://nimi.wiki.example/", "https://example.com/porn-statistics",
                "https://www.sextant.example/", "https://namu.wiki/w/x"}) {
            assertEquals(url, ALLOW, NavigationPolicy.entryDecision(url));
        }
    }

    @Test public void googleSignInCannotReturnToACommunity() {
        var policy = new NavigationPolicy("https://accounts.google.com/AccountChooser?continue="
                + encode("https://www.reddit.com/") + "&service=wise");
        assertEquals(BLOCK_COMMUNITY, policy.evaluate("https://www.reddit.com/"));
    }
}
