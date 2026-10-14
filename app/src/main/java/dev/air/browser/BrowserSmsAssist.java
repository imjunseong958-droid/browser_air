package dev.air.browser;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
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

/** Optional, five-minute SMS consent tied to one visible document and one Android result. */
final class BrowserSmsAssist {
    static final String EXTENSION_ID = "sms-assist@air.local";
    private static final String NATIVE_APP = "air_sms";
    private static final long WAIT_MS = 5 * 60_000;
    private enum Phase { STARTING, WAITING, CONSENT, CODES, SCANNING, FIELDS, FILLING }

    interface Source {
        boolean available(Context context);
        void start(Context context, Runnable success, Runnable failure);
    }
    private Source source = new Source() {
        @Override public boolean available(Context context) {
            return GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS;
        }
        @Override public void start(Context context, Runnable success, Runnable failure) {
            SmsRetriever.getClient(context).startSmsUserConsent(null)
                    .addOnSuccessListener(ignored -> success.run()).addOnFailureListener(ignored -> failure.run());
        }
    };

    private static final class Page {
        final WebExtension.Port port;
        String document;
        Page(WebExtension.Port port) { this.port = port; }
    }
    private static final class Request {
        final Page page;
        final String url;
        final String document;
        final String id;
        final long expires;
        Phase phase = Phase.STARTING;
        SmsCodeParser.Codes codes;
        String code;
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
    private BroadcastReceiver receiver;
    private AlertDialog dialog;
    private Request pending;
    private Request consentRequest;
    private Runnable expiry;
    private long sequence;
    private boolean resumed;
    private boolean destroyed;

    BrowserSmsAssist(BrowserActivity activity, Button button) {
        this.activity = activity; this.button = button;
        button.setEnabled(false);
        button.setOnClickListener(view -> { if (pending == null) begin(); else cancel(); });
        consentLauncher = activity.registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            Request request = consentRequest;
            consentRequest = null;
            if (!current(request)) { refresh(); return; }
            if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) { cancel(); return; }
            // Parse immediately and discard the full SMS. Codes stay in this request's memory only.
            acceptMessage(request, result.getData().getStringExtra(SmsRetriever.EXTRA_SMS_MESSAGE));
        });
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
        return page != null && page.document != null && visible != null
                && visible.sameOrigin(WebAddress.parse(page.port.sender.url)) ? page : null;
    }

    private boolean current(Request request) {
        return request != null && pending == request && !destroyed && !activity.isFinishing()
                && request.expires > SystemClock.elapsedRealtime() && visiblePage() == request.page
                && request.document.equals(request.page.document) && request.url.equals(activity.smsPageUrl());
    }

    private void begin() {
        Page page = visiblePage();
        if (!resumed || page == null || consentRequest != null) return;
        if (!source.available(activity)) { toast(R.string.sms_unavailable); return; }
        Request request = new Request(page, activity.smsPageUrl(), ++sequence);
        pending = request;
        expiry = () -> { if (pending == request) { cancel(); if (resumed) toast(R.string.sms_timeout); } };
        handler.postDelayed(expiry, WAIT_MS);
        try {
            receiver = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    if (!SmsRetriever.SMS_RETRIEVED_ACTION.equals(intent.getAction()) || !current(request)
                            || !resumed || request.phase != Phase.WAITING) return;
                    Status status = IntentCompat.getParcelableExtra(intent, SmsRetriever.EXTRA_STATUS, Status.class);
                    if (status == null) return;
                    if (status.getStatusCode() == CommonStatusCodes.TIMEOUT) { cancel(); toast(R.string.sms_timeout); return; }
                    if (status.getStatusCode() != CommonStatusCodes.SUCCESS) { cancel(); toast(R.string.sms_start_failed); return; }
                    Intent consent = IntentCompat.getParcelableExtra(intent, SmsRetriever.EXTRA_CONSENT_INTENT, Intent.class);
                    if (consent == null) { cancel(); return; }
                    stopReceiver();
                    request.phase = Phase.CONSENT;
                    consentRequest = request;
                    try { consentLauncher.launch(consent); }
                    catch (RuntimeException ignored) { consentRequest = null; cancel(); toast(R.string.sms_start_failed); }
                }
            };
            // Play services runs under another UID; its signature permission authenticates this receiver.
            ContextCompat.registerReceiver(activity, receiver, new IntentFilter(SmsRetriever.SMS_RETRIEVED_ACTION),
                    SmsRetriever.SEND_PERMISSION, null, ContextCompat.RECEIVER_EXPORTED);
            source.start(activity, () -> {
                if (!current(request) || request.phase != Phase.STARTING) return;
                request.phase = Phase.WAITING;
                refresh();
                toast(R.string.sms_wait_hint);
            }, () -> { if (pending == request) { cancel(); toast(R.string.sms_start_failed); } });
        } catch (RuntimeException ignored) { cancel(); toast(R.string.sms_start_failed); }
        refresh();
    }

    private void receive(Page page, JSONObject message) {
        String type = message.optString("type");
        if (type.equals("ready")) {
            String document = message.optString("document");
            if (!document.matches("[A-Za-z0-9-]{1,64}")) return;
            if (pending != null && pending.page == page && !document.equals(pending.document)) cancel();
            page.document = document;
            refresh();
            return;
        }
        Request request = pending;
        if (!current(request) || !resumed || request.page != page
                || !request.id.equals(message.optString("request"))
                || !request.document.equals(message.optString("document"))
                || !request.url.equals(message.optString("href"))) return;
        if (type.equals("fields") && request.phase == Phase.SCANNING) chooseField(request, message);
        else if (type.equals("filled") && request.phase == Phase.FILLING) {
            boolean success = message.optBoolean("success");
            if (!success) showFallback(request);
            else { cancel(); toast(R.string.sms_filled); }
        }
    }

    private void acceptMessage(Request request, String message) {
        if (!current(request) || request.phase != Phase.CONSENT) return;
        WebAddress address = WebAddress.parse(request.url);
        request.codes = SmsCodeParser.parse(message, address == null ? null : address.host());
        request.phase = Phase.CODES;
        if (resumed) chooseCode(request);
    }

    private void chooseCode(Request request) {
        if (!current(request) || !resumed) return;
        List<String> codes = request.codes.values();
        if (codes.isEmpty()) { cancel(); toast(R.string.sms_no_code); return; }
        if (request.codes.unambiguous()) { scan(request, codes.get(0)); return; }
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
        post(request, "fill", field);
        // Do not retain a code while waiting for an acknowledgement.
        request.code = null;
        expireResponse(request, Phase.FILLING);
    }

    private void post(Request request, String type, String field) {
        try {
            JSONObject message = new JSONObject().put("type", type).put("request", request.id)
                    .put("document", request.document).put("href", request.url);
            if (field != null) message.put("field", field).put("code", request.code);
            request.page.port.postMessage(message);
        } catch (JSONException | RuntimeException ignored) { cancel(); toast(R.string.sms_input_failed); }
    }

    private void expireResponse(Request request, Phase phase) {
        handler.postDelayed(() -> {
            if (pending == request && request.phase == phase) { cancel(); if (resumed) toast(R.string.sms_input_failed); }
        }, 5000);
    }

    private void showFallback(Request request) {
        String code = request.code;
        cancel();
        if (code == null) { toast(R.string.sms_input_failed); return; }
        dialog = new AlertDialog.Builder(activity).setTitle(R.string.sms_manual_title)
                .setMessage(activity.getString(R.string.sms_manual_body, code))
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
        refresh();
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

    void refresh() {
        if (destroyed) return;
        button.setText(pending == null ? R.string.sms_assist
                : pending.phase == Phase.STARTING ? R.string.sms_preparing
                : pending.phase == Phase.WAITING ? R.string.sms_waiting : R.string.sms_filling);
        button.setEnabled(pending != null || (visiblePage() != null && consentRequest == null && dialog == null));
    }

    void pause() {
        resumed = false;
        if (pending == null || pending.phase != Phase.CONSENT) cancel();
    }

    void resume() {
        resumed = true;
        if (pending != null && !current(pending)) cancel();
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
