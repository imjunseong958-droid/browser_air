package dev.air.navigation;

import static dev.air.navigation.NavigationPolicy.Decision.*;
import static org.junit.Assert.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

public final class TaskNavigationTest {
    private static final String FORM = "https://docs.google.com/forms/d/e/form_A/viewform?usp=send_form";
    private static final String OTHER_FORM = "https://docs.google.com/forms/d/e/form_B/viewform";
    private NavigationPolicy loaded(String url) {
        var policy = new NavigationPolicy(url);
        policy.pageLoaded(url);
        return policy;
    }
    private String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private String login(String returning) {
        return "https://accounts.google.com/AccountChooser?continue=" + encode(returning) + "&service=wise";
    }
    private String wrapper(String destination) { return "https://www.google.com/url?q=" + encode(destination); }

    @Test public void mailWrappersAreResolvedToTheCheckedDestination() {
        assertEquals(FORM, NavigationPolicy.resolveLink(wrapper(FORM)));
        assertEquals(ALLOW, NavigationPolicy.entryDecision(wrapper(wrapper(FORM))));
        assertEquals(BLOCK_SEARCH, NavigationPolicy.entryDecision(wrapper("https://google.com/search?q=test")));
        assertEquals(ALLOW, loaded(FORM).evaluate(wrapper(FORM), false, true));
        assertEquals(BLOCK_SEARCH, loaded(FORM).evaluate(wrapper("https://google.com/"), false, true));
    }

    @Test public void malformedOrAmbiguousWrappersCannotChooseAnotherDestination() {
        for (String url : new String[] {
                "https://www.google.com/url", wrapper("javascript:alert(1)"), wrapper("intent://other"),
                wrapper(FORM) + "&q=" + encode(OTHER_FORM), wrapper(FORM) + "&url=" + encode(OTHER_FORM),
                "https://www.google.com:444/url?q=" + encode(FORM),
                "http://www.google.com/url?q=" + encode(FORM)}) {
            assertEquals(url, BLOCK_INVALID, NavigationPolicy.entryDecision(url));
        }
        String nested = FORM;
        for (int i = 0; i < 5; i++) nested = wrapper(nested);
        assertEquals(BLOCK_INVALID, NavigationPolicy.entryDecision(nested));
        String lookalike = "https://www.google.com.evil.test/url?q=" + encode(FORM);
        assertEquals(lookalike, NavigationPolicy.resolveLink(lookalike));
    }

    @Test public void utilityPagesAreNotMislabelledAsSearch() {
        assertEquals(ALLOW, NavigationPolicy.entryDecision("https://www.google.com/maps/place/Seoul"));
        assertEquals(ALLOW, NavigationPolicy.entryDecision("https://www.google.com/forms/about/"));
        assertEquals(ALLOW, NavigationPolicy.entryDecision(FORM));
        assertEquals(ALLOW, NavigationPolicy.entryDecision("https://forms.gle/example"));
    }

    @Test public void discoveryIsBlockedEvenOnTheCurrentHostOrAfterApproval() {
        assertEquals(BLOCK_SEARCH, loaded("https://github.com/org/project").evaluate("https://github.com/search?q=x", false, true));
        for (String url : new String[] {"https://youtube.com/", "https://youtube.com/results?search_query=x",
                "https://youtube.com/feed/subscriptions", "https://x.com/home", "https://x.com/explore",
                "https://docs.google.com/forms/", "https://drive.google.com/drive/u/0/my-drive"}) {
            assertEquals(url, BLOCK_SEARCH, NavigationPolicy.entryDecision(url));
            assertFalse(loaded(FORM).approveContinuation(url));
        }
        assertEquals(ALLOW, NavigationPolicy.entryDecision("https://youtube.com/watch?v=example"));
    }

    @Test public void aFormIncludesItsSubmissionAndCompletionButNotOtherForms() {
        var policy = loaded(FORM);
        assertEquals(ALLOW, policy.evaluate("https://docs.google.com/forms/d/e/form_A/formResponse", false, true));
        assertEquals(ALLOW, policy.evaluate("https://docs.google.com/forms/d/e/form_A/viewform?edit2=token", false, true));
        assertEquals(CONFIRM_EXTERNAL, policy.evaluate(OTHER_FORM, false, true));
        assertEquals(BLOCK_SITE, policy.evaluate(OTHER_FORM, false, false));
        assertEquals(BLOCK_SITE, policy.evaluate(OTHER_FORM, true, false));
        assertEquals(BLOCK_SITE, policy.evaluateFrame(OTHER_FORM));
        assertEquals(CONFIRM_EXTERNAL, policy.evaluate("https://docs.google.com/document/d/doc_A/edit", false, true));
    }

    @Test public void resourceIdentityUsesNormalizedPathsAndSeparatesPublishedIds() {
        var policy = loaded(FORM);
        for (String path : new String[] {"/forms/d/e/form_A/../../form_B/viewform",
                "/forms/d/e/form_A/%2e%2e/%2e%2e/form_B/viewform", "/forms/d/form_A/viewform"}) {
            assertEquals(BLOCK_SITE, policy.evaluate("https://docs.google.com" + path, false, false));
        }
        assertEquals(ALLOW, loaded("https://drive.google.com/file/d/file_A/view")
                .evaluate("https://drive.google.com/open?id=file_A", false, true));
        assertEquals(CONFIRM_EXTERNAL, loaded("https://drive.google.com/file/d/file_A/view")
                .evaluate("https://drive.google.com/open?id=file_B", false, true));
    }

    @Test public void formsAccountChooserAndChallengesCanReturnWithoutOAuthParameters() {
        var policy = loaded(FORM);
        assertEquals(ALLOW, policy.evaluate(login(FORM), false, true));
        assertTrue(policy.isAuthenticating());
        assertEquals(ALLOW, policy.evaluate("https://accounts.google.com/v3/signin/challenge/pwd", false, true));
        assertEquals(ALLOW, policy.evaluateFrame("https://accounts.google.com/signin/challenge"));
        assertEquals(ALLOW, policy.evaluate(FORM + "&authuser=0", true, false));
        assertFalse(policy.isAuthenticating());
        assertEquals(BLOCK_SITE, policy.evaluateFrame("https://accounts.google.com/signin/challenge"));
    }

    @Test public void aWebsiteCanReturnFromGoogleSignInToItsOwnAccountPage() {
        var policy = loaded("https://shop.example/orders");
        assertEquals(ALLOW, policy.evaluate(login("https://shop.example/account"), false, true));
        assertEquals(ALLOW, policy.evaluate("https://shop.example/account", true, false));
        assertFalse(policy.isAuthenticating());
    }

    @Test public void signInCannotChangeItsReturnOrTurnIntoAccountManagement() {
        var policy = loaded(FORM);
        assertEquals(BLOCK_SITE, policy.evaluate(login(OTHER_FORM), false, true));
        assertEquals(BLOCK_SITE, policy.evaluate("https://unknown.example/login?continue=" + encode(FORM), false, true));
        assertEquals(ALLOW, policy.evaluate(login(FORM), false, true));
        assertEquals(BLOCK_SITE, policy.evaluateFrame(login(OTHER_FORM)));
        assertEquals(BLOCK_SITE, policy.evaluateFrame(login(FORM) + "&continue=" + encode(OTHER_FORM)));
        for (String url : new String[] {login(OTHER_FORM), login(FORM) + "&continue=" + encode(OTHER_FORM),
                "https://accounts.google.com/ManageAccount", "https://other.example/", "https://accounts.google.com:444/signin/challenge"}) {
            assertEquals(url, BLOCK_SITE, policy.evaluate(url, true, false));
        }
    }

    @Test public void signInAsTheFirstDocumentDoesNotReplaceTheOriginalTask() {
        var policy = new NavigationPolicy("https://forms.gle/example");
        assertEquals(ALLOW, policy.evaluate(FORM, true, false));
        assertEquals(ALLOW, policy.evaluate(login(FORM), true, false));
        policy.pageLoaded(login(FORM));
        assertEquals("docs.google.com", policy.lockedHost());
        assertEquals(ALLOW, policy.evaluate(FORM, true, false));
        assertFalse(policy.isAuthenticating());
        assertEquals(BLOCK_SITE, policy.evaluate(OTHER_FORM, true, false));
    }

    @Test public void aShortFormCanRedirectStraightToItsScopedSignIn() {
        var policy = new NavigationPolicy("https://forms.gle/example");
        assertEquals(ALLOW, policy.evaluate(login(FORM), true, false));
        policy.pageLoaded(login(FORM));
        assertEquals(ALLOW, policy.evaluate(FORM, true, false));
        var direct = loaded(login(FORM));
        assertEquals("docs.google.com", direct.lockedHost());
        assertEquals(ALLOW, direct.evaluate(FORM, true, false));
    }

    @Test public void repeatingSignInRequestsDoesNotExtendTheTimeLimit() {
        var now = new AtomicLong();
        var policy = new NavigationPolicy(FORM, now::get);
        policy.pageLoaded(FORM);
        assertEquals(ALLOW, policy.evaluate(login(FORM), false, true));
        now.addAndGet(10 * 60 * 1000);
        assertEquals(ALLOW, policy.evaluate(login(FORM), false, true));
        now.addAndGet(6 * 60 * 1000);
        assertFalse(policy.isAuthenticating());
        assertEquals(BLOCK_EXPIRED, policy.evaluate(FORM, true, false));
        assertEquals(BLOCK_EXPIRED, policy.evaluate(login(FORM), false, true));
        assertEquals(BLOCK_SITE, policy.evaluateFrame("https://accounts.google.com/signin/challenge"));
    }

    @Test public void oauthCannotUseAnotherDocumentOnTheSameHostAsItsReturn() {
        String authorize = "https://identity.example/oauth/authorize?client_id=test&response_type=code&redirect_uri=";
        var policy = loaded(FORM);
        assertEquals(BLOCK_SITE, policy.evaluate(authorize + encode(OTHER_FORM), false, true));
        assertEquals(ALLOW, policy.evaluate(authorize + encode(FORM), false, true));
    }

    @Test public void onlyAnExplicitContinuationOpensANewTaskAndItDoesNotAccumulateSites() {
        var policy = loaded(FORM);
        String first = "https://event.example/ticket";
        String second = "https://files.example/receipt";
        assertEquals(CONFIRM_EXTERNAL, policy.evaluate(first, false, true));
        assertEquals(BLOCK_SITE, policy.evaluate(first, false, false));
        assertTrue(policy.approveContinuation(first));
        assertEquals(ALLOW, policy.evaluate(first, false, false));
        policy.pageLoaded(first);
        assertEquals(ALLOW, policy.evaluate(FORM, false, true));
        assertTrue(policy.approveContinuation(second));
        policy.pageLoaded(second);
        assertEquals(BLOCK_SITE, policy.evaluate(first, false, false));
        assertEquals(ALLOW, policy.evaluate(FORM, false, true));
    }

    @Test public void approvedLinksResolveOnlyTheirInitialServerRedirects() {
        var policy = loaded(FORM);
        assertTrue(policy.approveContinuation("https://short.example/link"));
        assertEquals(BLOCK_SITE, policy.evaluate("https://other.example/script", false, false));
        assertEquals(BLOCK_SITE, policy.evaluate("http://other.example/downgrade", true, false));
        assertEquals(BLOCK_SEARCH, policy.evaluate("https://google.com/search?q=x", true, false));
        for (int i = 0; i < 8; i++) assertEquals(ALLOW, policy.evaluate("https://hop" + i + ".example/final", true, false));
        assertEquals(BLOCK_SITE, policy.evaluate("https://overflow.example/", true, false));
        policy.pageLoaded("https://hop7.example/final");
        assertEquals(BLOCK_SITE, policy.evaluate("https://after.example/", true, false));
    }

    @Test public void returningToTheOriginalPageAlsoEndsContinuationResolution() {
        var policy = loaded(FORM);
        assertTrue(policy.approveContinuation("https://short.example/link"));
        assertEquals(ALLOW, policy.evaluate(FORM, true, false));
        policy.pageLoaded(FORM);
        assertEquals(BLOCK_SITE, policy.evaluate("https://unrelated.example/", true, false));
    }
}
