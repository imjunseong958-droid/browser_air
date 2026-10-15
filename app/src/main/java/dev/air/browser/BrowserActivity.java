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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.WebRequestError;
import org.mozilla.geckoview.WebResponse;

public final class BrowserActivity extends AppCompatActivity {
    private ActivityBrowserBinding ui;
    private NavigationPolicy policy;
    private String entryUrl;
    private String currentUrl;
    private Boolean secure;
    private boolean shuttingDown;
    private boolean canGoBack;
    private CustomTabsSessionToken client;
    private BrowserPrompts prompts;
    private BrowserDownloads downloads;
    private BrowserPermissions permissions;
    private FirebaseAuthBridge firebaseAuth;
    private BrowserSmsAssist smsAssist;
    private NavigationConfirmation pendingNavigation;
    private final List<GeckoSession> sessions = new ArrayList<>();
    private final Map<GeckoSession, String> locations = new IdentityHashMap<>();
    private final Map<GeckoSession, Boolean> backStates = new IdentityHashMap<>();
    private final Map<GeckoSession, Boolean> securityStates = new IdentityHashMap<>();

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ui = ActivityBrowserBinding.inflate(getLayoutInflater());
        setContentView(ui.getRoot());
        AirInsets.apply(this, ui.root);
        prompts = new BrowserPrompts(this);
        downloads = new BrowserDownloads(this);
        permissions = new BrowserPermissions(this);
        smsAssist = new BrowserSmsAssist(this, ui.smsAssistButton);
        ui.finishButton.setOnClickListener(view -> finish());
        ui.backButton.setOnClickListener(view -> navigateBack());
        ui.retryButton.setOnClickListener(view -> startSession());
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
        secure = null;
        canGoBack = false;
        ui.errorPanel.setVisibility(View.GONE);
        updateSiteLabel(entryUrl);
        GeckoSession session = createSession();
        sessions.add(session);
        session.open(((AirApplication) getApplication()).runtime());
        ui.geckoView.setSession(session);
        ((AirApplication) getApplication()).smsExtension().accept(
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
                showError(R.string.engine_crash, R.string.engine_crash_body, true);
            }
            @Override public void onKill(@NonNull GeckoSession killed) {
                showError(R.string.engine_crash, R.string.engine_crash_body, true);
            }
            @Override public void onFirstContentfulPaint(@NonNull GeckoSession painted) {
                if (!sessions.isEmpty() && painted == sessions.get(0)) policy.pageLoaded(locations.get(painted));
            }
            @Override public void onExternalResponse(@NonNull GeckoSession source, @NonNull WebResponse response) {
                downloads.offer(response);
            }
        });
        session.setPermissionDelegate(permissions);
        smsAssist.attach(session);
        return session;
    }

    private final class NavigationDelegate implements GeckoSession.NavigationDelegate {
        @Override public @Nullable GeckoResult<AllowOrDeny> onLoadRequest(
                @NonNull GeckoSession source, @NonNull LoadRequest request) {
            if (shuttingDown || !sessions.contains(source)) return GeckoResult.fromValue(AllowOrDeny.DENY);
            if (firebaseAuth != null && firebaseAuth.handle(request.uri)) return GeckoResult.fromValue(AllowOrDeny.DENY);
            if (request.uri.equals("about:blank") && (sessions.indexOf(source) > 0 || request.target == TARGET_WINDOW_NEW)) {
                return GeckoResult.fromValue(AllowOrDeny.ALLOW);
            }
            if (pendingNavigation != null) return GeckoResult.fromValue(AllowOrDeny.DENY);
            String original = unwrapCallbackIntent(request.uri);
            String url = NavigationPolicy.resolveLink(original);
            NavigationPolicy.Decision decision = policy.evaluate(url, request.isRedirect, request.hasUserGesture);
            if (decision == NavigationPolicy.Decision.AUTH_CALLBACK) {
                handleCallback(url, source);
                return GeckoResult.fromValue(AllowOrDeny.DENY);
            }
            if (decision == NavigationPolicy.Decision.CONFIRM_EXTERNAL) {
                return confirmNavigation(source, url, !url.equals(original) || request.target == TARGET_WINDOW_NEW);
            }
            if (decision != NavigationPolicy.Decision.ALLOW) {
                showBlocked(decision);
                return GeckoResult.fromValue(AllowOrDeny.DENY);
            }
            if (!url.equals(original)) {
                // Load the checked destination itself; the wrapper cannot choose a different redirect.
                ui.root.post(() -> { if (!shuttingDown && sessions.contains(source)) source.loadUri(url); });
                return GeckoResult.fromValue(AllowOrDeny.DENY);
            }
            return GeckoResult.fromValue(AllowOrDeny.ALLOW);
        }

        @Override public @Nullable GeckoResult<AllowOrDeny> onSubframeLoadRequest(
                @NonNull GeckoSession source, @NonNull LoadRequest request) {
            // A frame may render CAPTCHA/authentication, but cannot become another browser.
            return GeckoResult.fromValue(policy.evaluateFrame(request.uri) == NavigationPolicy.Decision.ALLOW
                    ? AllowOrDeny.ALLOW : AllowOrDeny.DENY);
        }

        @Override public void onLocationChange(@NonNull GeckoSession source, @Nullable String url,
                @NonNull List<GeckoSession.PermissionDelegate.ContentPermission> permissions, @NonNull Boolean gesture) {
            if (url == null || url.equals("about:blank")) return;
            locations.put(source, url);
            if (source == active()) {
                currentUrl = url;
                secure = securityStates.get(source);
                updateSiteLabel(url);
            }
            smsAssist.locationChanged(source, url);
        }

        @Override public void onCanGoBack(@NonNull GeckoSession source, boolean available) {
            backStates.put(source, available);
            if (source == active()) canGoBack = available;
        }

        @Override public @Nullable GeckoResult<GeckoSession> onNewSession(@NonNull GeckoSession parent, @NonNull String url) {
            // A bounded child session preserves window.opener/postMessage for sign-in popups.
            // Gecko itself opens the returned session; returning an open session is an API error.
            if (sessions.size() >= 3 || (!url.equals("about:blank") && !url.isEmpty()
                    && policy.evaluate(url, false, true) != NavigationPolicy.Decision.ALLOW)) {
                showBlocked(NavigationPolicy.Decision.BLOCK_SITE);
                return null;
            }
            GeckoSession child = createSession();
            sessions.add(child);
            ui.root.post(() -> {
                if (!shuttingDown && sessions.contains(child) && child.isOpen()) {
                    ui.geckoView.setSession(child);
                    smsAssist.visibleSessionChanged();
                    child.setActive(true);
                    canGoBack = false;
                }
            });
            return GeckoResult.fromValue(child);
        }

        @Override public @Nullable GeckoResult<String> onLoadError(
                @NonNull GeckoSession source, @Nullable String uri, @NonNull WebRequestError error) {
            if (!shuttingDown) showError(R.string.connection_error, R.string.connection_error_body, true);
            notifyClient(CustomTabsCallback.NAVIGATION_FAILED);
            // No certificate bypass or web-based error page that could introduce new links.
            return null;
        }
    }

    private final class ProgressDelegate implements GeckoSession.ProgressDelegate {
        @Override public void onPageStart(@NonNull GeckoSession source, @NonNull String uri) {
            cancelNavigationConfirmation();
            smsAssist.pageStarted(source);
            securityStates.remove(source);
            if (!sessions.contains(source)) return;
            locations.put(source, uri);
            if (source == active()) {
                secure = null;
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
            if (success && !sessions.isEmpty() && source == sessions.get(0)) policy.pageLoaded(locations.get(source));
            if (source == active()) ui.progress.setVisibility(View.INVISIBLE);
            notifyClient(success ? CustomTabsCallback.NAVIGATION_FINISHED : CustomTabsCallback.NAVIGATION_FAILED);
        }
        @Override public void onSecurityChange(@NonNull GeckoSession source, @NonNull SecurityInformation info) {
            securityStates.put(source, info.isSecure && !info.isException);
            if (source == active()) secure = securityStates.get(source);
            smsAssist.refresh();
        }
    }

    private GeckoResult<AllowOrDeny> confirmNavigation(GeckoSession source, String url, boolean reload) {
        if (source != active() || isFinishing()) return GeckoResult.fromValue(AllowOrDeny.DENY);
        NavigationConfirmation request = new NavigationConfirmation(source, url, reload);
        pendingNavigation = request;
        request.dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.continue_task_title)
                .setMessage(getString(R.string.continue_task_body, navigationDestinationLabel(url)))
                .setPositiveButton(R.string.continue_task, (dialog, which) -> completeNavigationConfirmation(request, true))
                .setNegativeButton(R.string.return_to_task, (dialog, which) -> completeNavigationConfirmation(request, false))
                .setOnCancelListener(dialog -> completeNavigationConfirmation(request, false))
                .create();
        request.dialog.show();
        return request.result;
    }

    private String navigationDestinationLabel(String url) {
        WebAddress address = WebAddress.parse(url);
        String path = address.uri().getRawPath();
        if (path == null) path = "/";
        if (path.length() > 200) path = path.substring(0, 200) + "…";
        String port = address.port() == (address.scheme().equals("https") ? 443 : 80) ? "" : ":" + address.port();
        return address.scheme() + "://" + address.host() + port + "\n" + path;
    }

    private void completeNavigationConfirmation(NavigationConfirmation request, boolean accept) {
        if (pendingNavigation != request) return;
        pendingNavigation = null;
        boolean approved = accept && !isFinishing() && !shuttingDown && request.source == active()
                && request.source.isOpen() && request.policy == policy
                && java.util.Objects.equals(request.location, locations.get(request.source))
                && policy.approveContinuation(request.url);
        request.dialog.dismiss();
        request.result.complete(approved && !request.reload ? AllowOrDeny.ALLOW : AllowOrDeny.DENY);
        if (approved && request.reload) ui.root.post(() -> {
            if (!shuttingDown && request.source == active() && request.policy == policy) request.source.loadUri(request.url);
        });
    }

    private void cancelNavigationConfirmation() {
        if (pendingNavigation != null) completeNavigationConfirmation(pendingNavigation, false);
    }

    private final class NavigationConfirmation {
        final GeckoSession source;
        final String url;
        final boolean reload;
        final String location;
        final NavigationPolicy policy = BrowserActivity.this.policy;
        final GeckoResult<AllowOrDeny> result = new GeckoResult<>();
        AlertDialog dialog;
        NavigationConfirmation(GeckoSession source, String url, boolean reload) {
            this.source = source;
            this.url = url;
            this.reload = reload;
            location = locations.get(source);
        }
    }

    private String unwrapCallbackIntent(String input) {
        if (!input.startsWith("intent:")) return input;
        try {
            Intent decoded = Intent.parseUri(input, Intent.URI_INTENT_SCHEME);
            return decoded.getDataString() == null ? input : decoded.getDataString();
        } catch (java.net.URISyntaxException ignored) { return input; }
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
        WebAddress web = WebAddress.parse(url);
        if (web != null) {
            // Without a verified client, an HTTPS callback stays inside AIR; it never reopens a browser chooser.
            if (policy.completeWebCallback(url)) source.loadUri(url);
            return;
        }
        try {
            startActivity(callback);
            finish();
        } catch (ActivityNotFoundException ignored) {
            Toast.makeText(this, R.string.app_not_found, Toast.LENGTH_LONG).show();
        }
    }

    private void navigateBack() {
        if (ui.errorPanel.getVisibility() == View.VISIBLE) { finish(); return; }
        GeckoSession session = active();
        if (session != null && canGoBack) session.goBack();
        else if (sessions.size() > 1) closePopup(session);
        else finish();
    }

    private void closePopup(GeckoSession session) {
        cancelNavigationConfirmation();
        smsAssist.visibleSessionChanged();
        if (session != null) smsAssist.detach(session);
        if (session == null || sessions.size() <= 1) { finish(); return; }
        boolean visible = session == active();
        sessions.remove(session);
        locations.remove(session);
        backStates.remove(session);
        securityStates.remove(session);
        if (session.isOpen()) session.close();
        if (visible && active() != null) {
            ui.geckoView.setSession(active());
            active().setActive(true);
            currentUrl = locations.get(active());
            canGoBack = backStates.getOrDefault(active(), false);
            secure = securityStates.get(active());
            updateSiteLabel(currentUrl);
            smsAssist.refresh();
        }
    }

    private void updateSiteLabel(String url) {
        WebAddress address = WebAddress.parse(url);
        ui.hostLabel.setText(address == null ? getString(R.string.app_name) : address.host());
        ui.scopeLabel.setText(isAuthenticating() ? R.string.auth_session : R.string.site_locked);
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

    boolean isAuthenticating() { return (policy != null && policy.isAuthenticating()) || (firebaseAuth != null && firebaseAuth.isActive()); }

    GeckoSession smsSession() { return shuttingDown ? null : active(); }
    String smsPageUrl() { return currentUrl; }
    boolean smsPageAllowed() {
        WebAddress address = WebAddress.parse(currentUrl);
        return !shuttingDown && ui.errorPanel.getVisibility() != View.VISIBLE
                && ui.geckoView.getSession() == active() && address != null
                && ((address.scheme().equals("https") && Boolean.TRUE.equals(secure))
                    || (BuildConfig.DEBUG && address.scheme().equals("http")
                        && (address.host().equals("localhost") || address.host().equals("127.0.0.1"))));
    }

    private void showBlocked(NavigationPolicy.Decision decision) {
        int title = decision == NavigationPolicy.Decision.BLOCK_SEARCH ? R.string.search_blocked
                : decision == NavigationPolicy.Decision.BLOCK_EXPIRED ? R.string.auth_expired : R.string.navigation_blocked;
        Toast.makeText(this, title, Toast.LENGTH_LONG).show();
    }

    private void showEntryError(NavigationPolicy.Decision decision) {
        showError(decision == NavigationPolicy.Decision.BLOCK_SEARCH ? R.string.search_blocked : R.string.invalid_link,
                decision == NavigationPolicy.Decision.BLOCK_SEARCH ? R.string.search_blocked_body : R.string.invalid_link_body, false);
        ui.siteInfo.setEnabled(false);
    }

    private void showError(int title, int body, boolean retry) {
        smsAssist.visibleSessionChanged();
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
        cancelNavigationConfirmation();
        smsAssist.visibleSessionChanged();
        shuttingDown = true;
        ui.geckoView.releaseSession();
        for (GeckoSession session : sessions) {
            smsAssist.detach(session);
            if (session.isOpen()) session.close();
        }
        sessions.clear(); locations.clear(); backStates.clear(); securityStates.clear();
    }

    @Override protected void onPause() {
        cancelNavigationConfirmation();
        smsAssist.pause();
        if (active() != null) active().setActive(false);
        super.onPause();
    }
    @Override protected void onResume() { super.onResume(); if (active() != null) active().setActive(true); smsAssist.resume(); }
    @Override protected void onDestroy() {
        prompts.cancelPending();
        downloads.destroy();
        permissions.destroy();
        smsAssist.destroy();
        closeSessions();
        notifyClient(CustomTabsCallback.TAB_HIDDEN);
        super.onDestroy();
    }
}
