package dev.air.browser;

import dev.air.navigation.NavigationPolicy;
import org.mozilla.geckoview.GeckoSession;

/** One tab: its page session, the rules it opened under, and what the screen shows for it. */
final class BrowserTab {
    final GeckoSession session;
    /** A popup shares the rules of the tab that opened it, so a sign-in it carries returns the same way. */
    final NavigationPolicy policy;
    /** This tab's first page tells the rules that sign-ins from then on are the page's own. */
    final boolean ownsPolicy;
    /** The tab whose page opened this one; back from this tab's first page returns there. */
    final BrowserTab opener;
    /** A PDF opened from the device: the one {@code content:} address this tab may load. */
    final String localFile;
    /** Where the page is, as the rules last saw it; null before the first page starts. */
    String location;
    /** The address to show, retry or start over from, known before the page loads. */
    String url;
    String title;
    boolean canGoBack;
    boolean hasPage;
    boolean loading;
    int progress;
    String failedUrl;
    /** The error panel this tab shows over its page, or 0 for none. */
    int errorTitle;
    int errorBody;
    boolean errorRetry;
    long lastShown;
    int scrollY;

    BrowserTab(GeckoSession session, NavigationPolicy policy, boolean ownsPolicy, BrowserTab opener, String localFile) {
        this.session = session;
        this.policy = policy;
        this.ownsPolicy = ownsPolicy;
        this.opener = opener;
        this.localFile = localFile;
    }

    void clearError() { errorTitle = 0; errorBody = 0; errorRetry = false; }
}
