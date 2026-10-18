package dev.air.browser;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.Button;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.content.IntentCompat;
import com.google.android.gms.auth.api.phone.SmsRetriever;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.common.api.CommonStatusCodes;
import com.google.android.gms.common.api.Status;
import dev.air.navigation.WebAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.WebExtension;

/** One-message consent on every Android version, scoped to one foreground document. */
final class BrowserSmsAssist {
    static final String EXTENSION_ID = "sms-assist@air.local";
    private static final String NATIVE_APP = "air_sms";
    private static final long WAIT_MS = 5 * 60_000;
    // Allow a 10-second scan, or native input acknowledgement plus the 8-second completion wait.
    private static final long RESPONSE_MS = 15_000;
    private enum Phase { STARTING, WAITING, CONSENT, CODES, SCANNING, FIELDS, FILLING }

    private boolean available() {
        return GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(activity) == ConnectionResult.SUCCESS;
    }

    private static final class Page {
        final WebExtension.Port port;
        String document;
        String href;
        boolean hasTarget;
        boolean emptyTarget;
        boolean completed;
        Page(WebExtension.Port port) { this.port = port; }
    }
    private static final class Request {
        final Page page;
        final String url;
        final String document;
        final String id;
        final long expires;
        Phase phase = Phase.WAITING;
        SmsCodeParser.Codes codes;
        String code;
        boolean nativeInputUsed;
        volatile boolean cancelled;
        Request(Page page, String url, long sequence) {
            this.page = page; this.url = url; this.document = page.document;
            id = Long.toString(sequence); expires = SystemClock.elapsedRealtime() + WAIT_MS;
        }
    }

    private final BrowserActivity activity;
    private final Button button;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<GeckoSession, Page> pages = new IdentityHashMap<>();
    private final Set<GeckoSession> attached = Collections.newSetFromMap(new IdentityHashMap<>());
    private WebExtension extension;
    private final ActivityResultLauncher<Intent> consentLauncher;
    private Request consentRequest;
    private final SharedPreferences preferences;
    private boolean enabled;
    private BroadcastReceiver receiver;
    private AlertDialog dialog;
    private Request pending;
    private Runnable expiry;
    private long sequence;
    private boolean resumed;
    private boolean destroyed;

    BrowserSmsAssist(BrowserActivity activity, Button button) {
        this.activity = activity; this.button = button;
        preferences = activity.getSharedPreferences("sms_assist", Context.MODE_PRIVATE);
        enabled = preferences.getBoolean("enabled", false);
        button.setEnabled(false);
        consentLauncher = activity.registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            consentResult(result.getResultCode(), result.getData());
        });
        button.setOnClickListener(view -> {
            if (enabled) { setEnabled(false); cancel(); return; }
            if (!available()) { toast(R.string.sms_unavailable); return; }
            setEnabled(true);
            begin();
        });
    }

    private void consentResult(int resultCode, Intent data) {
        Request request = consentRequest;
        consentRequest = null;
        if (!current(request) || request.phase != Phase.CONSENT) { refresh(); return; }
        if (resultCode != Activity.RESULT_OK || data == null) {
            request.page.completed = true;
            cancel();
            return;
        }
        acceptMessage(request, data.getStringExtra(SmsRetriever.EXTRA_SMS_MESSAGE));
    }

    void attach(GeckoSession session) {
        attached.add(session);
        ((AirApplication) activity.getApplication()).smsExtension().accept(extension -> {
            if (destroyed || !attached.contains(session)) return;
            this.extension = extension;
            session.getWebExtensionController().setMessageDelegate(extension, new WebExtension.MessageDelegate() {
                @Override public void onConnect(@NonNull WebExtension.Port port) {
                    WebExtension.MessageSender sender = port.sender;
                    if (destroyed || !attached.contains(session) || sender.session != session || !sender.isTopLevel()
                            || sender.environmentType != WebExtension.MessageSender.ENV_TYPE_CONTENT_SCRIPT
                            || !EXTENSION_ID.equals(sender.webExtension.id)) { port.disconnect(); return; }
                    Page page = new Page(port);
                    Page previous = pages.put(session, page);
                    if (pending != null && pending.page == previous) cancel();
                    if (previous != null) previous.port.disconnect();
                    port.setDelegate(new WebExtension.PortDelegate() {
                        @Override public void onPortMessage(@NonNull Object value, @NonNull WebExtension.Port connected) {
                            if (value instanceof JSONObject message && pages.get(session) == page) receive(page, message);
                        }
                        @Override public void onDisconnect(@NonNull WebExtension.Port connected) {
                            if (pages.get(session) == page) pages.remove(session);
                            if (pending != null && pending.page == page) cancel();
                            refresh();
                        }
                    });
                }
            }, NATIVE_APP);
        }, ignored -> { if (!destroyed) refresh(); });
    }

    private Page visiblePage() {
        if (destroyed || !activity.smsPageAllowed()) return null;
        Page page = pages.get(activity.smsSession());
        WebAddress visible = WebAddress.parse(activity.smsPageUrl());
        return page != null && page.document != null && activity.smsPageUrl().equals(page.href) && visible != null
                && visible.sameOrigin(WebAddress.parse(page.port.sender.url)) ? page : null;
    }

    private boolean current(Request request) {
        return request != null && pending == request && !destroyed && !activity.isFinishing()
                && request.expires > SystemClock.elapsedRealtime() && visiblePage() == request.page
                && request.document.equals(request.page.document) && request.url.equals(activity.smsPageUrl());
    }

    private void begin() {
        Page page = visiblePage();
        if (!enabled || !resumed || page == null || !page.hasTarget || pending != null || consentRequest != null || dialog != null) return;
        if (!available()) return;
        Request request = new Request(page, activity.smsPageUrl(), ++sequence);
        request.phase = Phase.STARTING;
        pending = request;
        expiry = () -> { if (pending == request) { page.completed = true; cancel(); if (resumed) toast(R.string.sms_timeout); } };
        handler.postDelayed(expiry, WAIT_MS);
        try {
            receiver = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    if (!current(request) || !resumed || request.phase != Phase.WAITING || !available()) return;
                    if (!SmsRetriever.SMS_RETRIEVED_ACTION.equals(intent.getAction())) return;
                    Status status = IntentCompat.getParcelableExtra(intent, SmsRetriever.EXTRA_STATUS, Status.class);
                    if (status == null) return;
                    if (status.getStatusCode() != CommonStatusCodes.SUCCESS) {
                        page.completed = true; cancel(); return;
                    }
                    Intent consent = IntentCompat.getParcelableExtra(intent, SmsRetriever.EXTRA_CONSENT_INTENT, Intent.class);
                    if (consent == null || consentRequest != null) return;
                    stopReceiver();
                    request.phase = Phase.CONSENT;
                    consentRequest = request;
                    try { consentLauncher.launch(consent); }
                    catch (RuntimeException ignored) { consentRequest = null; page.completed = true; cancel(); toast(R.string.sms_start_failed); }
                }
            };
            // Only Play services can send this broadcast; AIR requests no SMS permission.
            ContextCompat.registerReceiver(activity, receiver, new IntentFilter(SmsRetriever.SMS_RETRIEVED_ACTION),
                    SmsRetriever.SEND_PERMISSION, null, ContextCompat.RECEIVER_EXPORTED);
            SmsRetriever.getClient(activity).startSmsUserConsent(null)
                    .addOnSuccessListener(ignored -> {
                        if (current(request) && request.phase == Phase.STARTING) { request.phase = Phase.WAITING; refresh(); }
                    }).addOnFailureListener(ignored -> {
                        if (pending == request) { page.completed = true; cancel(); toast(R.string.sms_start_failed); }
                    });
        } catch (RuntimeException ignored) { page.completed = true; cancel(); toast(R.string.sms_start_failed); }
        refresh();
    }

    private void receive(Page page, JSONObject message) {
        String type = message.optString("type");
        if (type.equals("ready") || type.equals("state")) {
            String document = message.optString("document");
            if (!document.matches("[A-Za-z0-9-]{1,64}")) return;
            if (type.equals("state") && !document.equals(page.document)) return;
            String href = message.optString("href");
            WebAddress address = WebAddress.parse(href);
            if (address == null || !address.sameOrigin(WebAddress.parse(page.port.sender.url))) return;
            if (pending != null && pending.page == page && !document.equals(pending.document)) cancel();
            page.document = document;
            page.href = href;
            boolean hasTarget = message.optBoolean("hasTarget");
            boolean emptyTarget = message.optBoolean("emptyTarget");
            if ((!page.hasTarget && hasTarget) || (!page.emptyTarget && emptyTarget)) page.completed = false;
            page.emptyTarget = emptyTarget;
            page.hasTarget = hasTarget;
            // Keep the consent window across same-document transitions to an unlabelled OTP widget.
            // Navigation, backgrounding and the five-minute expiry still cancel it.
            refresh();
            return;
        }
        Request request = pending;
        if (!current(request) || !resumed || request.page != page
                || !request.id.equals(message.optString("request"))
                || !request.document.equals(message.optString("document"))
                || !request.url.equals(message.optString("href"))) return;
        if (type.equals("input") && request.phase == Phase.FILLING) input(request);
        else if (type.equals("fields") && request.phase == Phase.SCANNING) chooseField(request, message);
        else if (type.equals("filled") && request.phase == Phase.FILLING) {
            boolean success = message.optBoolean("success");
            // A submitted SPA can remove/hide its widget without navigating. End quietly;
            // disappearance does not prove that input or account verification succeeded.
            boolean departed = message.optBoolean("submitted") && message.optBoolean("departed");
            if (!success && !departed) showFallback(request);
            else if (success && !message.optBoolean("submitted")) showFallback(request, true);
            else {
                page.completed = true;
                cancel();
            }
        }
    }

    private void acceptMessage(Request request, String message) {
        if (!current(request) || request.phase != Phase.CONSENT) return;
        WebAddress address = WebAddress.parse(request.url);
        request.codes = SmsCodeParser.parse(message, address == null ? null : address.host());
        // Consent grants one message only; a mismatch requires a fresh receive window.
        if (request.codes.values().isEmpty()) {
            request.codes = null;
            request.page.completed = true;
            cancel();
            toast(R.string.sms_no_code);
            return;
        }
        stopReceiver();
        request.phase = Phase.CODES;
        if (resumed) chooseCode(request);
    }

    private void chooseCode(Request request) {
        if (!current(request) || !resumed) return;
        List<String> codes = request.codes.values();
        if (codes.isEmpty()) { cancel(); toast(R.string.sms_no_code); return; }
        if (codes.size() == 1) { scan(request, codes.get(0)); return; }
        dialog = new AlertDialog.Builder(activity).setTitle(R.string.sms_choose_code)
                .setItems(codes.toArray(new String[0]), (d, which) -> { dialog = null; scan(request, codes.get(which)); })
                .setNegativeButton(R.string.cancel, (d, which) -> cancel())
                .setOnCancelListener(d -> cancel()).show();
    }

    private void scan(Request request, String code) {
        if (!current(request) || !resumed) { cancel(); return; }
        request.code = code;
        request.codes = null;
        request.phase = Phase.SCANNING;
        updateButton();
        post(request, "scan", null);
        expireResponse(request, Phase.SCANNING);
    }

    private void chooseField(Request request, JSONObject message) {
        JSONArray fields = message.optJSONArray("fields");
        if (fields == null || fields.length() == 0 || fields.length() > 8) { showFallback(request); return; }
        List<String> ids = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < fields.length(); i++) {
            JSONObject field = fields.optJSONObject(i);
            if (field == null) { showFallback(request); return; }
            String id = field.optString("id");
            if (!id.matches("[0-7]") || ids.contains(id)) { showFallback(request); return; }
            ids.add(id);
            String label = field.optString("label").replaceAll("[\\p{Cntrl}\\p{Cf}]", "");
            if (label.length() > 80) label = label.substring(0, 80);
            labels.add(activity.getString(R.string.sms_field_label, i + 1,
                    label.isBlank() ? activity.getString(R.string.sms_unnamed_field) : label));
        }
        String automatic = message.optString("automatic");
        if (ids.contains(automatic)) { fill(request, automatic); return; }
        request.phase = Phase.FIELDS;
        dialog = new AlertDialog.Builder(activity).setTitle(R.string.sms_choose_field)
                .setItems(labels.toArray(new String[0]), (d, which) -> { dialog = null; fill(request, ids.get(which)); })
                .setNegativeButton(R.string.cancel, (d, which) -> cancel())
                .setOnCancelListener(d -> cancel()).show();
    }

    private void fill(Request request, String field) {
        if (!current(request) || !resumed) { cancel(); return; }
        request.phase = Phase.FILLING;
        updateButton();
        post(request, "fill", field);
        expireResponse(request, Phase.FILLING);
    }

    private void input(Request request) {
        if (request.nativeInputUsed || !SmsCodeParser.valid(request.code)) { inputResult(request, false); return; }
        request.nativeInputUsed = true;
        String code = request.code;
        try {
            InputConnection connection = activity.smsSession().getTextInput().onCreateInputConnection(new EditorInfo());
            if (connection == null) { inputResult(request, false); return; }
            // Software keyboards can move Gecko's editor off the UI thread.
            Handler editor = connection.getHandler();
            (editor == null ? handler : editor).post(() -> {
                boolean inserted = false;
                try {
                    if (request.cancelled || request.expires <= SystemClock.elapsedRealtime()) return;
                    CharSequence before = connection.getTextBeforeCursor(11, 0);
                    CharSequence selected = connection.getSelectedText(0);
                    CharSequence after = connection.getTextAfterCursor(11, 0);
                    // Do not overwrite an edit that arrived after the extension checked the field.
                    if (!request.cancelled && before != null && after != null) {
                        String existing = before.toString() + (selected == null ? "" : selected.toString()) + after;
                        if (existing.isEmpty()) {
                            inserted = connection.commitText(code, 1);
                        } else if (existing.equals(code) && connection.setSelection(0, existing.length())) {
                            // Identical replacements can suppress input events. Clear only our code.
                            inserted = !request.cancelled && connection.commitText("", 1)
                                    && !request.cancelled && connection.commitText(code, 1);
                        }
                    }
                } catch (RuntimeException ignored) { }
                boolean accepted = inserted;
                handler.post(() -> {
                    if (current(request) && resumed && request.phase == Phase.FILLING) inputResult(request, accepted);
                });
            });
        } catch (RuntimeException ignored) { inputResult(request, false); }
    }

    private void inputResult(Request request, boolean inserted) {
        try {
            request.page.port.postMessage(new JSONObject().put("type", "input-result").put("request", request.id)
                    .put("document", request.document).put("href", request.url).put("success", inserted));
        } catch (JSONException | RuntimeException ignored) { showFallback(request); }
    }

    private void post(Request request, String type, String field) {
        try {
            JSONObject message = new JSONObject().put("type", type).put("request", request.id)
                    .put("document", request.document).put("href", request.url);
            if (request.code != null) message.put("code", request.code);
            if (field != null) message.put("field", field);
            request.page.port.postMessage(message);
        } catch (JSONException | RuntimeException ignored) { request.page.completed = true; cancel(); toast(R.string.sms_input_failed); }
    }

    private void expireResponse(Request request, Phase phase) {
        handler.postDelayed(() -> {
            if (pending == request && request.phase == phase) {
                if (resumed && current(request)) showFallback(request);
                else cancel();
            }
        }, RESPONSE_MS);
    }

    private void showFallback(Request request) {
        showFallback(request, false);
    }

    private void showFallback(Request request, boolean checkSite) {
        String code = request.code;
        request.page.completed = true;
        cancel();
        if (code == null) { toast(R.string.sms_input_failed); return; }
        dialog = new AlertDialog.Builder(activity).setTitle(checkSite ? R.string.sms_check_site : R.string.sms_manual_title)
                .setMessage(activity.getString(checkSite ? R.string.sms_check_site_body : R.string.sms_manual_body, code))
                .setPositiveButton(R.string.okay, (d, which) -> cancel())
                .setOnCancelListener(d -> cancel()).show();
        // The short-lived dialog closes on navigation, backgrounding or expiry.
        expiry = () -> { if (dialog != null) cancel(); };
        handler.postDelayed(expiry, 60_000);
        refresh();
    }

    private void stopReceiver() {
        if (receiver == null) return;
        try { activity.unregisterReceiver(receiver); } catch (IllegalArgumentException ignored) { }
        receiver = null;
    }

    private void cancel() {
        if (pending != null) {
            pending.cancelled = true;
            try {
                pending.page.port.postMessage(new JSONObject().put("type", "cancel").put("request", pending.id)
                        .put("document", pending.document).put("href", pending.url));
            } catch (JSONException | RuntimeException ignored) { }
            pending.code = null; pending.codes = null;
        }
        pending = null;
        stopReceiver();
        if (expiry != null) handler.removeCallbacks(expiry);
        expiry = null;
        if (dialog != null) { dialog.dismiss(); dialog = null; }
        updateButton();
    }

    void pageStarted(GeckoSession session) {
        if ((pending != null && pending.page.port.sender.session == session) || session == activity.smsSession()) cancel();
        Page page = pages.remove(session);
        if (page != null) page.port.disconnect();
        refresh();
    }

    void locationChanged(GeckoSession session, String url) {
        if (pending != null && pending.page.port.sender.session == session && !pending.url.equals(url)) cancel();
        if (dialog != null && session == activity.smsSession()) cancel();
        refresh();
    }

    void visibleSessionChanged() { cancel(); }

    void detach(GeckoSession session) {
        attached.remove(session);
        pageStarted(session);
        if (extension != null) session.getWebExtensionController().setMessageDelegate(extension, null, NATIVE_APP);
    }

    private void setEnabled(boolean value) {
        enabled = value;
        preferences.edit().putBoolean("enabled", value).apply();
        Page page = visiblePage();
        if (page != null) page.completed = false;
    }

    void refresh() {
        if (destroyed) return;
        Page page = visiblePage();
        if (resumed && enabled && page != null && page.hasTarget && !page.completed
                && pending == null && dialog == null && consentRequest == null && available()) begin();
        updateButton();
    }

    private void updateButton() {
        if (destroyed) return;
        Page page = visiblePage();
        button.setText(!enabled ? R.string.sms_assist
                : pending == null ? R.string.sms_enabled
                : pending.phase == Phase.STARTING ? R.string.sms_preparing
                : pending.phase == Phase.WAITING ? R.string.sms_waiting_consent
                : pending.phase == Phase.SCANNING ? R.string.sms_waiting_field : R.string.sms_filling);
        button.setEnabled(consentRequest == null && (enabled || page != null));
    }

    void pause() {
        resumed = false;
        if (pending == null || pending != consentRequest || pending.phase != Phase.CONSENT) cancel();
    }

    void resume() {
        resumed = true;
        if (pending != null && !current(pending)) cancel();
        if (enabled && !available()) setEnabled(false);
        if (pending != null && pending.phase == Phase.CODES) chooseCode(pending);
        refresh();
    }

    void destroy() {
        destroyed = true;
        cancel();
        consentRequest = null;
        handler.removeCallbacksAndMessages(null);
        for (GeckoSession session : new ArrayList<>(attached)) detach(session);
        pages.clear();
    }

    private void toast(int message) { Toast.makeText(activity, message, Toast.LENGTH_LONG).show(); }
}
