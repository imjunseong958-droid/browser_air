package dev.air.browser;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.browser.customtabs.CustomTabsCallback;
import androidx.browser.customtabs.CustomTabsSessionToken;
import dev.air.browser.databinding.ActivityBrowserBinding;
import dev.air.navigation.NavigationPolicy;
import dev.air.navigation.WebAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.WebRequestError;
import org.mozilla.geckoview.WebResponse;

public final class BrowserActivity extends AppCompatActivity {
    /** The page and two popups; a further new-window link opens in the page showing now. */
    private static final int MAX_SESSIONS = 3;
    private ActivityBrowserBinding ui;
    private NavigationPolicy policy;
    private String entryUrl;
    private String currentUrl;
    private String failedUrl;
    private Boolean secure;
    private boolean shuttingDown;
    private boolean canGoBack;
    private AlertDialog appPrompt;
    private CustomTabsSessionToken client;
    private BrowserPrompts prompts;
    private BrowserDownloads downloads;
    private BrowserPermissions permissions;
    private FirebaseAuthBridge firebaseAuth;
    private final List<GeckoSession> sessions = new ArrayList<>();
    private final Map<GeckoSession, String> locations = new IdentityHashMap<>();
    private final Map<GeckoSession, Boolean> backStates = new IdentityHashMap<>();
    private final Map<GeckoSession, Boolean> securityStates = new IdentityHashMap<>();
    private final Set<GeckoSession> sessionsWithPage = Collections.newSetFromMap(new IdentityHashMap<>());

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ui = ActivityBrowserBinding.inflate(getLayoutInflater());
        setContentView(ui.getRoot());
        AirInsets.apply(this, ui.root);
        prompts = new BrowserPrompts(this);
        downloads = new BrowserDownloads(this);
        permissions = new BrowserPermissions(this);
        ui.finishButton.setOnClickListener(view -> finish());
        ui.backButton.setOnClickListener(view -> navigateBack());
        ui.retryButton.setOnClickListener(view -> retry());
        ui.siteInfo.setOnClickListener(view -> showConnectionDetails());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { navigateBack(); }
        });
        entryUrl = NavigationPolicy.resolveLink(getIntent().getDataString());
        client = CustomTabsSessionToken.getSessionTokenFromIntent(getIntent());
        NavigationPolicy.Decision entry = NavigationPolicy.entryDecision(entryUrl);
        if (entry != NavigationPolicy.Decision.ALLOW) {
            showEntryError(entry);
            return;
        }
        startSession();
        notifyClient(CustomTabsCallback.TAB_SHOWN);
    }

    private GeckoSession active() { return sessions.isEmpty() ? null : sessions.get(sessions.size() - 1); }

    private void startSession() {
        if (NavigationPolicy.entryDecision(entryUrl) != NavigationPolicy.Decision.ALLOW) return;
        closeSessions();
        shuttingDown = false;
        prompts.cancelPending();
        policy = new NavigationPolicy(entryUrl);
        firebaseAuth = FirebaseAuthBridge.capture(this, entryUrl);
        currentUrl = entryUrl;
        failedUrl = null;
        showSecurity(null);
        canGoBack = false;
        ui.errorPanel.setVisibility(View.GONE);
        updateSiteLabel(entryUrl);
        GeckoSession session = createSession();
        sessions.add(session);
        session.open(((AirApplication) getApplication()).runtime());
        ui.geckoView.setSession(session);
        ((AirApplication) getApplication()).extensionsReady().accept(
                ignored -> loadEntry(session), ignored -> loadEntry(session));
    }

    private void loadEntry(GeckoSession session) {
        if (!isFinishing() && !shuttingDown && sessions.contains(session) && session.isOpen()) session.loadUri(entryUrl);
    }

    private GeckoSession createSession() {
        GeckoSession session = new GeckoSession(new GeckoSessionSettings.Builder()
                .usePrivateMode(false)
                .suspendMediaWhenInactive(true)
                .build());
        session.setNavigationDelegate(new NavigationDelegate());
        session.setProgressDelegate(new ProgressDelegate());
        session.setPromptDelegate(prompts);
        session.setContentDelegate(new GeckoSession.ContentDelegate() {
            @Override public void onCloseRequest(@NonNull GeckoSession requested) { closePopup(requested); }
            @Override public void onCrash(@NonNull GeckoSession crashed) {
                failedUrl = null;
                showError(R.string.engine_crash, R.string.engine_crash_body, true);
            }
            @Override public void onKill(@NonNull GeckoSession killed) {
                failedUrl = null;
                showError(R.string.engine_crash, R.string.engine_crash_body, true);
            }
            @Override public void onFirstContentfulPaint(@NonNull GeckoSession painted) {
                if (!sessions.isEmpty() && painted == sessions.get(0)) policy.pageLoaded();
            }
            @Override public void onExternalResponse(@NonNull GeckoSession source, @NonNull WebResponse response) {
                downloads.offer(response);
            }
        });
        session.setPermissionDelegate(permissions);
        return session;
    }

    private final class NavigationDelegate implements GeckoSession.NavigationDelegate {
        @Override public @Nullable GeckoResult<AllowOrDeny> onLoadRequest(
                @NonNull GeckoSession source, @NonNull LoadRequest request) {
            if (shuttingDown || !sessions.contains(source)) return GeckoResult.fromValue(AllowOrDeny.DENY);
            if (firebaseAuth != null && firebaseAuth.handle(request.uri)) return GeckoResult.fromValue(AllowOrDeny.DENY);
            String url = NavigationPolicy.resolveLink(unwrapIntent(request.uri));
            NavigationPolicy.Decision decision = NavigationPolicy.pageChange(locations.get(source), url);
            if (decision == NavigationPolicy.Decision.ALLOW) decision = policy.evaluate(url);
            if (!carryOut(decision, url, source, request.hasUserGesture)) return GeckoResult.fromValue(AllowOrDeny.DENY);
            if (!url.equals(request.uri)) {
                // Load the checked destination itself; the wrapper cannot choose a different redirect.
                ui.root.post(() -> { if (!shuttingDown && sessions.contains(source)) source.loadUri(url); });
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
            String previous = locations.get(source);
            NavigationPolicy.Decision moved = NavigationPolicy.pageChange(previous, url);
            if (previous != null && moved != NavigationPolicy.Decision.ALLOW) {
                // The page changed its own address without a load request (YouTube does this); undo the move.
                showBlocked(moved);
                ui.root.post(() -> returnTo(source, previous));
                return;
            }
            locations.put(source, url);
            if (source == active()) {
                currentUrl = url;
                showSecurity(securityStates.get(source));
                updateSiteLabel(url);
            }
        }

        @Override public void onCanGoBack(@NonNull GeckoSession source, boolean available) {
            backStates.put(source, available);
            if (source == active()) canGoBack = available;
        }

        @Override public @Nullable GeckoResult<GeckoSession> onNewSession(@NonNull GeckoSession parent, @NonNull String url) {
            String target = NavigationPolicy.resolveLink(unwrapIntent(url));
            NavigationPolicy.Decision decision = url.isEmpty() ? NavigationPolicy.Decision.ALLOW : policy.evaluate(target);
            if (!carryOut(decision, target, parent, true)) return null;
            if (sessions.size() >= MAX_SESSIONS) {
                if (url.isEmpty() || url.equals("about:blank")) return null;
                ui.root.post(() -> {
                    GeckoSession showing = active();
                    if (!shuttingDown && showing != null && showing.isOpen()) showing.loadUri(target);
                });
                return null;
            }
            // A bounded child session preserves window.opener/postMessage for sign-in popups.
            // Gecko itself opens the returned session; returning an open session is an API error.
            GeckoSession child = createSession();
            sessions.add(child);
            ui.root.post(() -> {
                if (!shuttingDown && sessions.contains(child) && child.isOpen()) {
                    ui.geckoView.setSession(child);
                    child.setActive(true);
                    canGoBack = false;
                }
            });
            return GeckoResult.fromValue(child);
        }

        @Override public @Nullable GeckoResult<String> onLoadError(
                @NonNull GeckoSession source, @Nullable String uri, @NonNull WebRequestError error) {
            if (!shuttingDown) {
                failedUrl = uri;
                showError(R.string.connection_error, R.string.connection_error_body, true);
            }
            notifyClient(CustomTabsCallback.NAVIGATION_FAILED);
            // No certificate bypass or web-based error page that could introduce new links.
            return null;
        }
    }

    private final class ProgressDelegate implements GeckoSession.ProgressDelegate {
        @Override public void onPageStart(@NonNull GeckoSession source, @NonNull String uri) {
            securityStates.remove(source);
            if (!sessions.contains(source)) return;
            locations.put(source, uri);
            if (source == active()) {
                showSecurity(null);
                if (ui.geckoView.getSession() != source) {
                    ui.geckoView.setSession(source);
                    source.setActive(true);
                }
                ui.errorPanel.setVisibility(View.GONE);
                ui.progress.setVisibility(View.VISIBLE);
                ui.progress.setProgress(8);
            }
            notifyClient(CustomTabsCallback.NAVIGATION_STARTED);
        }
        @Override public void onProgressChange(@NonNull GeckoSession source, int progress) {
            if (source == active()) ui.progress.setProgress(progress);
        }
        @Override public void onPageStop(@NonNull GeckoSession source, boolean success) {
            if (success) sessionsWithPage.add(source);
            if (success && !sessions.isEmpty() && source == sessions.get(0)) policy.pageLoaded();
            if (source == active()) ui.progress.setVisibility(View.INVISIBLE);
            notifyClient(success ? CustomTabsCallback.NAVIGATION_FINISHED : CustomTabsCallback.NAVIGATION_FAILED);
        }
        @Override public void onSecurityChange(@NonNull GeckoSession source, @NonNull SecurityInformation info) {
            securityStates.put(source, info.isSecure && !info.isException);
            if (source == active()) showSecurity(securityStates.get(source));
        }
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
    private boolean carryOut(NavigationPolicy.Decision decision, String url, GeckoSession source, boolean userGesture) {
        if (decision == NavigationPolicy.Decision.ALLOW) return true;
        if (decision == NavigationPolicy.Decision.AUTH_CALLBACK) handleCallback(url, source);
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

    private void handleCallback(String url, GeckoSession source) {
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
            policy.signInReturned();
            source.loadUri(url);
            return;
        }
        try {
            startActivity(callback);
            finish();
        } catch (ActivityNotFoundException ignored) {
            Toast.makeText(this, R.string.app_not_found, Toast.LENGTH_LONG).show();
        }
    }

    private void returnTo(GeckoSession session, String previous) {
        if (shuttingDown || !sessions.contains(session) || !session.isOpen()) return;
        if (Boolean.TRUE.equals(backStates.get(session))) session.goBack();
        else session.loadUri(previous);
    }

    private void retry() {
        GeckoSession session = active();
        if (failedUrl != null && session != null && session.isOpen()) {
            ui.errorPanel.setVisibility(View.GONE);
            session.loadUri(failedUrl);
            failedUrl = null;
            return;
        }
        // The engine stopped, so this starts over from the page that was showing.
        if (NavigationPolicy.entryDecision(currentUrl) == NavigationPolicy.Decision.ALLOW) entryUrl = currentUrl;
        startSession();
    }

    private void navigateBack() {
        GeckoSession session = active();
        if (ui.errorPanel.getVisibility() == View.VISIBLE) {
            // A link that failed to load leaves the page it came from in place behind the panel.
            boolean pageBehind = failedUrl != null && sessionsWithPage.contains(session);
            failedUrl = null;
            if (pageBehind) {
                ui.errorPanel.setVisibility(View.GONE);
                showSecurity(currentUrl.startsWith("https:"));
            } else if (sessions.size() > 1) {
                ui.errorPanel.setVisibility(View.GONE);
                closePopup(session);
            } else finish();
            return;
        }
        if (session != null && canGoBack) session.goBack();
        else if (sessions.size() > 1) closePopup(session);
        else finish();
    }

    private void closePopup(GeckoSession session) {
        if (session == null || sessions.size() <= 1) { finish(); return; }
        boolean visible = session == active();
        sessions.remove(session);
        locations.remove(session);
        backStates.remove(session);
        securityStates.remove(session);
        sessionsWithPage.remove(session);
        if (session.isOpen()) session.close();
        if (visible && active() != null) {
            ui.geckoView.setSession(active());
            active().setActive(true);
            currentUrl = locations.get(active());
            canGoBack = backStates.getOrDefault(active(), false);
            showSecurity(securityStates.get(active()));
            updateSiteLabel(currentUrl);
        }
    }

    private void updateSiteLabel(String url) {
        WebAddress address = WebAddress.parse(url);
        ui.hostLabel.setText(address == null ? getString(R.string.app_name) : address.host());
    }

    /** True for an encrypted connection, false for an unencrypted one, and null while it is not known yet. */
    private void showSecurity(Boolean secure) {
        this.secure = secure;
        ui.securityIcon.setVisibility(secure == null ? View.GONE : View.VISIBLE);
        if (secure != null) ui.securityIcon.setImageResource(secure ? R.drawable.ic_lock : R.drawable.ic_lock_open);
    }

    private void showConnectionDetails() {
        if (currentUrl == null) return;
        String status = getString(secure == null ? R.string.security_pending
                : secure ? R.string.secure_connection : R.string.insecure_connection);
        new AlertDialog.Builder(this).setTitle(R.string.connection_details)
                .setMessage(status + "\n\n" + currentUrl)
                .setPositiveButton(R.string.okay, null).show();
    }

    String visibleHost() {
        WebAddress address = WebAddress.parse(currentUrl);
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

    private void showEntryError(NavigationPolicy.Decision decision) {
        if (decision == NavigationPolicy.Decision.BLOCK_COMMUNITY) {
            showError(R.string.site_blocked, R.string.community_blocked_body, false);
        } else if (decision == NavigationPolicy.Decision.BLOCK_ADULT) {
            showError(R.string.site_blocked, R.string.adult_blocked_body, false);
        } else {
            showError(decision == NavigationPolicy.Decision.BLOCK_SEARCH ? R.string.search_blocked : R.string.invalid_link,
                    decision == NavigationPolicy.Decision.BLOCK_SEARCH ? R.string.search_blocked_body : R.string.invalid_link_body, false);
        }
        ui.siteInfo.setEnabled(false);
        ui.siteInfo.setVisibility(View.INVISIBLE);
    }

    private void showError(int title, int body, boolean retry) {
        ui.errorTitle.setText(title);
        ui.errorBody.setText(body);
        ui.retryButton.setVisibility(retry ? View.VISIBLE : View.GONE);
        ui.errorPanel.setVisibility(View.VISIBLE);
        ui.progress.setVisibility(View.INVISIBLE);
    }

    private void notifyClient(int event) {
        if (client != null && client.getCallback() != null) client.getCallback().onNavigationEvent(event, null);
    }

    private void closeSessions() {
        shuttingDown = true;
        ui.geckoView.releaseSession();
        for (GeckoSession session : sessions) {
            if (session.isOpen()) session.close();
        }
        sessions.clear(); locations.clear(); backStates.clear(); securityStates.clear(); sessionsWithPage.clear();
    }

    @Override protected void onPause() {
        if (active() != null) active().setActive(false);
        super.onPause();
    }
    @Override protected void onResume() { super.onResume(); if (active() != null) active().setActive(true); }
    @Override protected void onDestroy() {
        if (appPrompt != null) appPrompt.dismiss();
        prompts.cancelPending();
        downloads.destroy();
        permissions.destroy();
        closeSessions();
        notifyClient(CustomTabsCallback.TAB_HIDDEN);
        super.onDestroy();
    }
}
