package dev.air.browser;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.browser.customtabs.CustomTabsCallback;
import androidx.browser.customtabs.CustomTabsSessionToken;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import dev.air.browser.databinding.ActivityBrowserBinding;
import dev.air.navigation.NavigationPolicy;
import dev.air.navigation.WebAddress;
import java.util.ArrayList;
import java.util.List;
import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.MediaSession;
import org.mozilla.geckoview.WebRequestError;
import org.mozilla.geckoview.WebResponse;

/**
 * Pages in tabs. A link held down can open in a new tab, a page's new-window link or popup opens one, and the tab
 * counter in the corner lists them. Back goes back a page, then closes the tab, then closes AIR from the last one.
 */
public final class BrowserActivity extends AppCompatActivity {
    /** A further new-window link opens in the tab showing now. */
    static final int MAX_TABS = 10;
    private ActivityBrowserBinding ui;
    private boolean fullScreen;
    private boolean keyboardOpen;
    private boolean counterAway;
    private boolean shuttingDown;
    private AlertDialog appPrompt;
    private AlertDialog linkMenu;
    private CustomTabsSessionToken client;
    private BrowserPrompts prompts;
    private BrowserDownloads downloads;
    private BrowserPermissions permissions;
    private FirebaseAuthBridge firebaseAuth;
    private TabsSheet tabsSheet;
    private ActivityResultLauncher<String[]> pdfPicker;
    private final List<BrowserTab> tabs = new ArrayList<>();
    private BrowserTab current;
    /** A page's new window, shown once Gecko has opened its session. */
    private BrowserTab opening;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ui = ActivityBrowserBinding.inflate(getLayoutInflater());
        setContentView(ui.getRoot());
        AirInsets.apply(this, ui.root);
        // Pages may draw beside a camera cutout; the padding below keeps ordinary pages clear of it.
        WindowManager.LayoutParams window = getWindow().getAttributes();
        window.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        getWindow().setAttributes(window);
        ViewCompat.setOnApplyWindowInsetsListener(ui.root, this::applyInsets);
        updateBars();
        prompts = new BrowserPrompts(this);
        downloads = new BrowserDownloads(this);
        permissions = new BrowserPermissions(this);
        tabsSheet = new TabsSheet(this, new SheetActions());
        pdfPicker = registerForActivityResult(new ActivityResultContracts.OpenDocument(), picked -> {
            if (picked != null) openFile(picked);
        });
        ui.retryButton.setOnClickListener(view -> retry());
        ui.tabsButton.setOnClickListener(view -> tabsSheet.show());
        ui.geckoView.setPullListener(new PullToRefresh());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (fullScreen && current != null) current.session.exitFullScreen();
                else navigateBack();
            }
        });
        client = CustomTabsSessionToken.getSessionTokenFromIntent(getIntent());
        Uri file = sharedPdf(getIntent());
        if (file != null) {
            openFile(file);
            return;
        }
        String entryUrl = NavigationPolicy.resolveLink(getIntent().getDataString());
        NavigationPolicy.Decision entry = NavigationPolicy.entryDecision(entryUrl);
        if (entry != NavigationPolicy.Decision.ALLOW) {
            showEntryError(entry);
            return;
        }
        firebaseAuth = FirebaseAuthBridge.capture(this, entryUrl);
        openTab(entryUrl, new NavigationPolicy(entryUrl), null, null);
        notifyClient(CustomTabsCallback.TAB_SHOWN);
    }

    /** A PDF another app handed over (Files, mail, a messenger), or null for a web link. AIR's own files never open. */
    private @Nullable Uri sharedPdf(Intent intent) {
        Uri data = intent.getData();
        if (data == null || !"content".equals(data.getScheme())) return null;
        String authority = data.getAuthority();
        return authority == null || authority.startsWith(getPackageName()) ? null : data;
    }

    private GeckoRuntime runtime() { return ((AirApplication) getApplication()).runtime(); }

    /** The address showing in the current tab, for prompts and tests. */
    @Nullable String currentUrl() { return current == null ? null : current.url; }

    /** Opens a tab and shows it; its first page loads once the built-in extension is ready, so YouTube is filtered. */
    private BrowserTab openTab(String url, NavigationPolicy policy, @Nullable BrowserTab opener, @Nullable String localFile) {
        BrowserTab tab = newTab(policy, true, opener, localFile);
        tab.url = url;
        tabs.add(tab);
        tab.session.open(runtime());
        show(tab);
        ((AirApplication) getApplication()).extensionsReady().accept(ignored -> load(tab, url), ignored -> load(tab, url));
        return tab;
    }

    private void load(BrowserTab tab, String url) {
        if (!isFinishing() && !shuttingDown && tabs.contains(tab) && tab.session.isOpen()) tab.session.loadUri(url);
    }

    /** A PDF from the device opens in the engine's PDF viewer; Gecko reads only granted files that start as a PDF. */
    private void openFile(Uri file) {
        if (tabs.size() >= MAX_TABS) { tabsFull(); return; }
        String address = file.toString();
        openTab(address, NavigationPolicy.forLocalFile(), null, address);
    }

    private void tabsFull() {
        Toast.makeText(this, getString(R.string.tabs_full, MAX_TABS), Toast.LENGTH_LONG).show();
    }

    private BrowserTab newTab(NavigationPolicy policy, boolean ownsPolicy, @Nullable BrowserTab opener, @Nullable String localFile) {
        GeckoSession session = new GeckoSession(new GeckoSessionSettings.Builder()
                .usePrivateMode(false)
                .suspendMediaWhenInactive(true)
                .build());
        BrowserTab tab = new BrowserTab(session, policy, ownsPolicy, opener, localFile);
        session.setNavigationDelegate(new NavigationDelegate(tab));
        session.setProgressDelegate(new ProgressDelegate(tab));
        session.setContentDelegate(new ContentDelegate(tab));
        session.setScrollDelegate(new GeckoSession.ScrollDelegate() {
            @Override public void onScrollChanged(@NonNull GeckoSession source, int scrollX, int scrollY) { scrolled(tab, scrollY); }
        });
        session.setPromptDelegate(prompts);
        session.setPermissionDelegate(permissions);
        session.setMediaSessionDelegate(new MediaSession.Delegate() {
            @Override public void onFullscreen(@NonNull GeckoSession source, @NonNull MediaSession media,
                    boolean enabled, @Nullable MediaSession.ElementMetadata video) {
                // A portrait video (a phone recording) stays upright; everything else turns sideways.
                if (enabled && fullScreen && tab == current && video != null && video.height > video.width) {
                    setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
                }
            }
        });
        return tab;
    }

    /** Puts a tab on screen; the others stay loaded but inactive, and their media pauses. */
    private void show(BrowserTab tab) {
        if (shuttingDown || !tabs.contains(tab)) return;
        if (current != tab) {
            setFullScreen(false);
            if (current != null && current.session.isOpen()) current.session.setActive(false);
            current = tab;
        }
        tab.lastShown = SystemClock.elapsedRealtime();
        if (tab.session.isOpen()) {
            if (ui.geckoView.getSession() != tab.session) ui.geckoView.setSession(tab.session);
            tab.session.setActive(true);
        }
        counterAway = false;
        renderTab();
        tabsSheet.refresh();
    }

    /** Shows the current tab's error panel and loading line, and the tab counter. */
    private void renderTab() {
        BrowserTab tab = current;
        if (tab != null && tab.errorTitle != 0) {
            ui.errorTitle.setText(tab.errorTitle);
            ui.errorBody.setText(tab.errorBody);
            ui.retryButton.setVisibility(tab.errorRetry ? View.VISIBLE : View.GONE);
            ui.errorPanel.setVisibility(View.VISIBLE);
        } else if (tab != null) {
            ui.errorPanel.setVisibility(View.GONE);
        }
        boolean loading = tab != null && tab.loading && tab.errorTitle == 0 && !fullScreen;
        ui.progress.setVisibility(loading ? View.VISIBLE : View.INVISIBLE);
        if (tab != null) ui.progress.setProgress(tab.progress);
        updateCounter();
    }

    /** The counter shows how many tabs are open. It steps aside for a full-screen video, the keyboard and scrolling down. */
    private void updateCounter() {
        ui.tabsButton.setText(String.valueOf(tabs.size()));
        ui.tabsButton.setContentDescription(getString(R.string.tabs_count, tabs.size()));
        boolean visible = !tabs.isEmpty() && !fullScreen && !keyboardOpen;
        ui.tabsButton.animate().cancel();
        if (!visible) {
            ui.tabsButton.setVisibility(View.GONE);
            return;
        }
        if (ui.tabsButton.getVisibility() == View.GONE) {
            ui.tabsButton.setAlpha(counterAway ? 0f : 1f);
            ui.tabsButton.setTranslationY(counterAway ? awayDistance() : 0f);
        }
        ui.tabsButton.setVisibility(View.VISIBLE);
        ui.tabsButton.setClickable(!counterAway);
        ui.tabsButton.animate().alpha(counterAway ? 0f : 1f).translationY(counterAway ? awayDistance() : 0f)
                .setDuration(180).start();
    }

    private float awayDistance() { return 72 * getResources().getDisplayMetrics().density; }

    /** Scrolling down moves the counter out of the way of the page; scrolling up or reaching the top brings it back. */
    private void scrolled(BrowserTab tab, int scrollY) {
        int moved = scrollY - tab.scrollY;
        tab.scrollY = scrollY;
        if (tab != current) return;
        boolean away = scrollY > 0 && (moved > 6 || (counterAway && moved >= -6));
        if (away != counterAway) {
            counterAway = away;
            updateCounter();
        }
    }

    /** A video's full screen hides the system bars and turns the screen sideways; leaving it restores both. */
    private void setFullScreen(boolean enabled) {
        if (fullScreen == enabled) return;
        fullScreen = enabled;
        if (enabled) {
            ui.progress.setVisibility(View.INVISIBLE);
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        } else {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        }
        updateBars();
        updateCounter();
    }

    @Override public void onConfigurationChanged(@NonNull Configuration config) {
        super.onConfigurationChanged(config);
        updateBars();
    }

    /**
     * Upright, the status bar keeps its strip above the page. Sideways, or in a video's full screen, the bars
     * hide and the page fills the screen; a swipe from the edge shows them for a moment.
     */
    private void updateBars() {
        boolean immersive = fullScreen
                || getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        WindowInsetsControllerCompat bars = WindowCompat.getInsetsController(getWindow(), ui.root);
        bars.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        if (immersive) bars.hide(WindowInsetsCompat.Type.systemBars());
        else bars.show(WindowInsetsCompat.Type.systemBars());
        // Any strip left beside a cutout blends into the bezel instead of showing as a white band.
        ui.root.setBackgroundColor(immersive ? Color.BLACK : ContextCompat.getColor(this, R.color.paper));
        ViewCompat.requestApplyInsets(ui.root);
    }

    /** Hidden bars report no space, so only a cutout pads a sideways page; a full-screen video uses every pixel. */
    private WindowInsetsCompat applyInsets(View view, WindowInsetsCompat insets) {
        Insets keyboard = insets.getInsets(WindowInsetsCompat.Type.ime());
        if (fullScreen) {
            view.setPadding(0, 0, 0, keyboard.bottom);
        } else {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, keyboard.bottom));
        }
        // The counter would sit on the keyboard, over the field being typed in.
        boolean typing = insets.isVisible(WindowInsetsCompat.Type.ime());
        if (typing != keyboardOpen) {
            keyboardOpen = typing;
            updateCounter();
        }
        return insets;
    }

    private final class NavigationDelegate implements GeckoSession.NavigationDelegate {
        private final BrowserTab tab;
        NavigationDelegate(BrowserTab tab) { this.tab = tab; }

        @Override public @Nullable GeckoResult<AllowOrDeny> onLoadRequest(
                @NonNull GeckoSession source, @NonNull LoadRequest request) {
            if (shuttingDown || !tabs.contains(tab)) return GeckoResult.fromValue(AllowOrDeny.DENY);
            // The device file this tab was opened for; every other local address stays refused.
            if (request.uri.equals(tab.localFile)) return GeckoResult.fromValue(AllowOrDeny.ALLOW);
            if (firebaseAuth != null && firebaseAuth.handle(request.uri)) return GeckoResult.fromValue(AllowOrDeny.DENY);
            String url = NavigationPolicy.resolveLink(unwrapIntent(request.uri));
            NavigationPolicy.Decision decision = NavigationPolicy.pageChange(tab.location, url);
            if (decision == NavigationPolicy.Decision.ALLOW) decision = tab.policy.evaluate(url);
            if (!carryOut(decision, url, tab, request.hasUserGesture)) return GeckoResult.fromValue(AllowOrDeny.DENY);
            if (!url.equals(request.uri)) {
                // Load the checked destination itself; the wrapper cannot choose a different redirect.
                ui.root.post(() -> load(tab, url));
                return GeckoResult.fromValue(AllowOrDeny.DENY);
            }
            return GeckoResult.fromValue(AllowOrDeny.ALLOW);
        }

        @Override public @Nullable GeckoResult<AllowOrDeny> onSubframeLoadRequest(
                @NonNull GeckoSession source, @NonNull LoadRequest request) {
            return GeckoResult.fromValue(NavigationPolicy.evaluateFrame(request.uri) == NavigationPolicy.Decision.ALLOW
                    ? AllowOrDeny.ALLOW : AllowOrDeny.DENY);
        }

        @Override public void onLocationChange(@NonNull GeckoSession source, @Nullable String url,
                @NonNull List<GeckoSession.PermissionDelegate.ContentPermission> permissions, @NonNull Boolean gesture) {
            if (url == null || url.equals("about:blank")) return;
            String previous = tab.location;
            NavigationPolicy.Decision moved = NavigationPolicy.pageChange(previous, url);
            if (previous != null && moved != NavigationPolicy.Decision.ALLOW) {
                // The page changed its own address without a load request (YouTube does this); undo the move.
                showBlocked(moved);
                ui.root.post(() -> returnTo(tab, previous));
                return;
            }
            tab.location = url;
            tab.url = url;
            tabsSheet.refresh();
        }

        @Override public void onCanGoBack(@NonNull GeckoSession source, boolean available) {
            tab.canGoBack = available;
        }

        @Override public @Nullable GeckoResult<GeckoSession> onNewSession(@NonNull GeckoSession parent, @NonNull String url) {
            String target = NavigationPolicy.resolveLink(unwrapIntent(url));
            NavigationPolicy.Decision decision = url.isEmpty() ? NavigationPolicy.Decision.ALLOW : tab.policy.evaluate(target);
            // A new window from a video to another video is the same recommendation hop as a link.
            if (decision == NavigationPolicy.Decision.ALLOW && !url.isEmpty()) decision = NavigationPolicy.pageChange(tab.location, target);
            if (!carryOut(decision, target, tab, true)) return null;
            if (tabs.size() >= MAX_TABS) {
                if (url.isEmpty() || url.equals("about:blank")) return null;
                ui.root.post(() -> { if (current != null) load(current, target); });
                return null;
            }
            // The new tab's session keeps window.opener/postMessage for sign-in popups, and shares this tab's rules.
            // Gecko itself opens the returned session; returning an open session is an API error.
            BrowserTab child = newTab(tab.policy, false, tab, null);
            child.url = url.isEmpty() ? null : target;
            tabs.add(child);
            ui.root.post(() -> {
                if (child.session.isOpen()) show(child);
                else opening = child; // Shown when its first page starts.
            });
            return GeckoResult.fromValue(child.session);
        }

        @Override public @Nullable GeckoResult<String> onLoadError(
                @NonNull GeckoSession source, @Nullable String uri, @NonNull WebRequestError error) {
            if (!shuttingDown) {
                tab.failedUrl = uri;
                if (uri != null && uri.equals(tab.localFile)) showError(tab, R.string.pdf_error, R.string.pdf_error_body, true);
                else showError(tab, R.string.connection_error, R.string.connection_error_body, true);
            }
            notifyClient(CustomTabsCallback.NAVIGATION_FAILED);
            // No certificate bypass or web-based error page that could introduce new links.
            return null;
        }
    }

    private final class ProgressDelegate implements GeckoSession.ProgressDelegate {
        private final BrowserTab tab;
        ProgressDelegate(BrowserTab tab) { this.tab = tab; }

        @Override public void onPageStart(@NonNull GeckoSession source, @NonNull String uri) {
            if (!tabs.contains(tab)) return;
            if (tab == opening) {
                opening = null;
                show(tab);
            }
            tab.location = uri;
            tab.clearError();
            tab.loading = true;
            tab.progress = 8;
            if (tab == current) {
                if (ui.geckoView.getSession() != source) {
                    ui.geckoView.setSession(source);
                    source.setActive(true);
                }
                renderTab();
            }
            notifyClient(CustomTabsCallback.NAVIGATION_STARTED);
        }
        @Override public void onProgressChange(@NonNull GeckoSession source, int progress) {
            tab.progress = progress;
            if (tab == current) ui.progress.setProgress(progress);
        }
        @Override public void onPageStop(@NonNull GeckoSession source, boolean success) {
            if (success) tab.hasPage = true;
            if (success && tab.ownsPolicy) tab.policy.pageLoaded();
            tab.loading = false;
            if (tab == current) ui.progress.setVisibility(View.INVISIBLE);
            notifyClient(success ? CustomTabsCallback.NAVIGATION_FINISHED : CustomTabsCallback.NAVIGATION_FAILED);
        }
    }

    private final class ContentDelegate implements GeckoSession.ContentDelegate {
        private final BrowserTab tab;
        ContentDelegate(BrowserTab tab) { this.tab = tab; }

        @Override public void onCloseRequest(@NonNull GeckoSession requested) { closeTab(tab); }
        @Override public void onFullScreen(@NonNull GeckoSession requested, boolean enabled) {
            if (tab == current || !enabled) setFullScreen(enabled);
        }
        @Override public void onCrash(@NonNull GeckoSession crashed) { stopped(); }
        @Override public void onKill(@NonNull GeckoSession killed) { stopped(); }
        private void stopped() {
            tab.failedUrl = null;
            tab.loading = false;
            showError(tab, R.string.engine_crash, R.string.engine_crash_body, true);
        }
        @Override public void onFirstContentfulPaint(@NonNull GeckoSession painted) {
            if (tab.ownsPolicy) tab.policy.pageLoaded();
        }
        @Override public void onExternalResponse(@NonNull GeckoSession source, @NonNull WebResponse response) {
            downloads.offer(response);
        }
        @Override public void onTitleChange(@NonNull GeckoSession source, @Nullable String title) {
            tab.title = title;
            tabsSheet.refresh();
        }
        @Override public void onContextMenu(@NonNull GeckoSession source, int screenX, int screenY,
                @NonNull ContextElement element) {
            if (element.linkUri != null) showLinkMenu(tab, element.linkUri);
        }
    }

    /** Holding a web link offers to open it in a new tab or copy it. */
    private void showLinkMenu(BrowserTab from, String link) {
        String url = NavigationPolicy.resolveLink(unwrapIntent(link));
        WebAddress address = WebAddress.parse(url);
        if (address == null || shuttingDown) return;
        if (linkMenu != null && linkMenu.isShowing()) linkMenu.dismiss();
        String[] actions = {getString(R.string.open_in_new_tab), getString(R.string.copy_link)};
        linkMenu = new AlertDialog.Builder(this)
                .setTitle(address.host())
                .setItems(actions, (dialog, which) -> {
                    if (which == 0) openLinkInNewTab(from, url);
                    else copyLink(url);
                })
                .show();
    }

    /** The new tab follows the same rules as a link: a blocked site or a video-to-video hop does not open. */
    private void openLinkInNewTab(BrowserTab from, String url) {
        NavigationPolicy.Decision decision = NavigationPolicy.entryDecision(url);
        if (decision == NavigationPolicy.Decision.ALLOW) decision = NavigationPolicy.pageChange(from.location, url);
        if (decision != NavigationPolicy.Decision.ALLOW) { showBlocked(decision); return; }
        if (tabs.size() >= MAX_TABS) { tabsFull(); return; }
        openTab(url, new NavigationPolicy(url), tabs.contains(from) ? from : null, null);
    }

    private void copyLink(String url) {
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(ClipData.newPlainText(WebAddress.parse(url).host(), url));
        // Android 13 and later confirm a copy themselves.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(this, R.string.link_copied, Toast.LENGTH_SHORT).show();
    }

    /** Only an intent's address is used: never its package, component, extras or fallback link. */
    private String unwrapIntent(String input) {
        if (!input.startsWith("intent:")) return input;
        try {
            Intent decoded = Intent.parseUri(input, Intent.URI_INTENT_SCHEME);
            return decoded.getDataString() == null ? input : decoded.getDataString();
        } catch (java.net.URISyntaxException ignored) { return input; }
    }

    /** Carries out a decision other than a plain load; true when the page itself may load. */
    private boolean carryOut(NavigationPolicy.Decision decision, String url, BrowserTab tab, boolean userGesture) {
        if (decision == NavigationPolicy.Decision.ALLOW) return true;
        if (decision == NavigationPolicy.Decision.AUTH_CALLBACK) handleCallback(url, tab);
        else if (decision == NavigationPolicy.Decision.OPEN_APP) openInApp(url, userGesture);
        else showBlocked(decision);
        return false;
    }

    /** Hands a phone, mail or app link to Android. A page that did not follow a tap has to ask first. */
    private void openInApp(String url, boolean userGesture) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE);
        if (userGesture) {
            startApp(intent);
            return;
        }
        if (appPrompt != null && appPrompt.isShowing()) return;
        appPrompt = new AlertDialog.Builder(this).setTitle(R.string.open_app_title).setMessage(visibleHost())
                .setPositiveButton(R.string.open_app_action, (dialog, which) -> startApp(intent))
                .setNegativeButton(R.string.cancel, null).show();
    }

    private void startApp(Intent intent) {
        try { startActivity(intent); }
        catch (ActivityNotFoundException ignored) { Toast.makeText(this, R.string.open_app_missing, Toast.LENGTH_LONG).show(); }
    }

    private void handleCallback(String url, BrowserTab tab) {
        Uri uri = Uri.parse(url);
        Intent callback = new Intent(Intent.ACTION_VIEW, uri)
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        String[] callers = AirCustomTabsService.callerPackages(client);
        for (String caller : callers) {
            if (caller.equals(getPackageName())) continue;
            try {
                startActivity(new Intent(callback).setPackage(caller));
                finish();
                return;
            } catch (ActivityNotFoundException ignored) { /* Try another package sharing the verified UID. */ }
        }
        if (WebAddress.parse(url) != null) {
            // Without a verified client, an HTTPS callback stays inside AIR; it never reopens a browser chooser.
            tab.policy.signInReturned();
            tab.session.loadUri(url);
            return;
        }
        try {
            startActivity(callback);
            finish();
        } catch (ActivityNotFoundException ignored) {
            Toast.makeText(this, R.string.app_not_found, Toast.LENGTH_LONG).show();
        }
    }

    private void returnTo(BrowserTab tab, String previous) {
        if (shuttingDown || !tabs.contains(tab) || !tab.session.isOpen()) return;
        if (tab.canGoBack) tab.session.goBack();
        else tab.session.loadUri(previous);
    }

    /** Pulling down from the top of a page shows a turning arrow; pulled far enough, letting go reloads the page. */
    private final class PullToRefresh implements AirGeckoView.PullListener {
        @Override public boolean canPull() {
            return !fullScreen && !shuttingDown && current != null && current.session.isOpen()
                    && ui.errorPanel.getVisibility() != View.VISIBLE;
        }

        @Override public void onPull(float distance) {
            float trigger = ui.geckoView.refreshDistance();
            float size = ui.pullIndicator.getHeight() > 0 ? ui.pullIndicator.getHeight() : 40 * getResources().getDisplayMetrics().density;
            float travel = Math.min(distance, trigger * 1.25f) * 0.6f;
            boolean ready = distance >= trigger;
            ui.pullIndicator.animate().cancel();
            ui.pullIndicator.setVisibility(View.VISIBLE);
            ui.pullIndicator.setTranslationY(travel - size);
            ui.pullIndicator.setRotation(distance / trigger * 270f);
            ui.pullIndicator.setAlpha(ready ? 1f : 0.35f + 0.5f * Math.min(1f, distance / trigger));
            float scale = ready ? 1f : 0.85f;
            ui.pullIndicator.setScaleX(scale);
            ui.pullIndicator.setScaleY(scale);
        }

        @Override public void onPullEnd(boolean refresh) {
            if (refresh && canPull()) current.session.reload();
            float size = ui.pullIndicator.getHeight();
            ui.pullIndicator.animate()
                    .translationY(-size)
                    .alpha(0f)
                    .rotationBy(refresh ? 360f : 0f)
                    .setDuration(refresh ? 450 : 200)
                    .withEndAction(() -> ui.pullIndicator.setVisibility(View.GONE))
                    .start();
        }
    }

    private void retry() {
        BrowserTab tab = current;
        if (tab == null) return;
        tab.clearError();
        renderTab();
        if (tab.failedUrl != null && tab.session.isOpen()) {
            String failed = tab.failedUrl;
            tab.failedUrl = null;
            tab.session.loadUri(failed);
            return;
        }
        // The engine stopped, so this tab starts over from the page it was showing.
        ui.geckoView.releaseSession();
        if (!tab.session.isOpen()) tab.session.open(runtime());
        ui.geckoView.setSession(tab.session);
        tab.session.setActive(true);
        tab.location = null;
        tab.canGoBack = false;
        tab.hasPage = false;
        if (tab.url != null) load(tab, tab.url);
    }

    private void navigateBack() {
        BrowserTab tab = current;
        if (tab == null) { finish(); return; }
        if (tab.errorTitle != 0) {
            // A link that failed to load leaves the page it came from in place behind the panel.
            boolean pageBehind = tab.failedUrl != null && tab.hasPage;
            tab.failedUrl = null;
            if (pageBehind) {
                tab.clearError();
                renderTab();
            } else closeTab(tab);
            return;
        }
        if (tab.canGoBack) tab.session.goBack();
        else closeTab(tab);
    }

    /** Closing the last tab closes AIR. A closed tab returns to the tab that opened it, or the one shown before. */
    private void closeTab(BrowserTab tab) {
        if (!tabs.contains(tab)) return;
        if (tabs.size() <= 1) { finish(); return; }
        tabs.remove(tab);
        if (tab == opening) opening = null;
        if (tab == current) {
            BrowserTab next = tab.opener != null && tabs.contains(tab.opener) ? tab.opener : lastShown();
            show(next);
        }
        if (tab.session.isOpen()) tab.session.close();
        renderTab();
        tabsSheet.refresh();
    }

    private BrowserTab lastShown() {
        BrowserTab latest = tabs.get(tabs.size() - 1);
        for (BrowserTab tab : tabs) if (tab.lastShown > latest.lastShown) latest = tab;
        return latest;
    }

    String visibleHost() {
        WebAddress address = WebAddress.parse(currentUrl());
        return address == null ? getString(R.string.app_name) : address.host();
    }

    private void showBlocked(NavigationPolicy.Decision decision) {
        int title = decision == NavigationPolicy.Decision.BLOCK_SEARCH ? R.string.search_blocked
                : decision == NavigationPolicy.Decision.BLOCK_COMMUNITY || decision == NavigationPolicy.Decision.BLOCK_ADULT
                ? R.string.site_blocked
                : decision == NavigationPolicy.Decision.BLOCK_RECOMMENDATION ? R.string.recommendation_blocked
                : R.string.invalid_link;
        Toast.makeText(this, title, Toast.LENGTH_LONG).show();
    }

    /** A refused entry never starts the engine; there is no tab to go back to. */
    private void showEntryError(NavigationPolicy.Decision decision) {
        int title;
        int body;
        if (decision == NavigationPolicy.Decision.BLOCK_COMMUNITY) {
            title = R.string.site_blocked;
            body = R.string.community_blocked_body;
        } else if (decision == NavigationPolicy.Decision.BLOCK_ADULT) {
            title = R.string.site_blocked;
            body = R.string.adult_blocked_body;
        } else if (decision == NavigationPolicy.Decision.BLOCK_SEARCH) {
            title = R.string.search_blocked;
            body = R.string.search_blocked_body;
        } else {
            title = R.string.invalid_link;
            body = R.string.invalid_link_body;
        }
        ui.errorTitle.setText(title);
        ui.errorBody.setText(body);
        ui.retryButton.setVisibility(View.GONE);
        ui.errorPanel.setVisibility(View.VISIBLE);
        ui.progress.setVisibility(View.INVISIBLE);
    }

    private void showError(BrowserTab tab, int title, int body, boolean retry) {
        tab.errorTitle = title;
        tab.errorBody = body;
        tab.errorRetry = retry;
        if (tab == current) {
            setFullScreen(false);
            renderTab();
        }
    }

    private void notifyClient(int event) {
        if (client != null && client.getCallback() != null) client.getCallback().onNavigationEvent(event, null);
    }

    private void closeTabs() {
        shuttingDown = true;
        ui.geckoView.releaseSession();
        for (BrowserTab tab : tabs) {
            if (tab.session.isOpen()) tab.session.close();
        }
        tabs.clear();
        current = null;
    }

    private final class SheetActions implements TabsSheet.Actions {
        @Override public List<BrowserTab> tabs() { return tabs; }
        @Override public BrowserTab current() { return current; }

        @Override public String label(BrowserTab tab) {
            if (tab.title != null && !tab.title.isBlank()) return tab.title.strip();
            return place(tab);
        }

        @Override public String place(BrowserTab tab) {
            if (tab.localFile != null && (tab.url == null || tab.url.equals(tab.localFile))) return getString(R.string.pdf_file);
            WebAddress address = WebAddress.parse(tab.url);
            return address == null ? getString(R.string.untitled_tab) : address.host();
        }

        @Override public void show(BrowserTab tab) { BrowserActivity.this.show(tab); }
        @Override public void close(BrowserTab tab) { closeTab(tab); }

        @Override public void newTab() {
            if (tabs.size() >= MAX_TABS) { tabsFull(); return; }
            SearchCard.show(BrowserActivity.this, url -> {
                if (shuttingDown) return false;
                openTab(url, new NavigationPolicy(url), null, null);
                return true;
            });
        }

        @Override public void openPdf() {
            if (tabs.size() >= MAX_TABS) { tabsFull(); return; }
            try { pdfPicker.launch(new String[] {"application/pdf"}); }
            catch (ActivityNotFoundException ignored) { Toast.makeText(BrowserActivity.this, R.string.open_app_missing, Toast.LENGTH_LONG).show(); }
        }
    }

    @Override protected void onPause() {
        if (current != null && current.session.isOpen()) current.session.setActive(false);
        super.onPause();
    }
    @Override protected void onResume() {
        super.onResume();
        if (current != null && current.session.isOpen()) current.session.setActive(true);
    }
    @Override protected void onDestroy() {
        if (appPrompt != null) appPrompt.dismiss();
        if (linkMenu != null) linkMenu.dismiss();
        tabsSheet.dismiss();
        prompts.cancelPending();
        downloads.destroy();
        permissions.destroy();
        closeTabs();
        notifyClient(CustomTabsCallback.TAB_HIDDEN);
        super.onDestroy();
    }
}
