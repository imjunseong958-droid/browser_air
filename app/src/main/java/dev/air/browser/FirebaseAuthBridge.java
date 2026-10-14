package dev.air.browser;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.widget.Toast;
import dev.air.navigation.FirebaseAuthFlow;
import java.security.MessageDigest;

/** Routes only the SDK's two fixed callback activities after checking the original certificate and event. */
final class FirebaseAuthBridge {
    private final BrowserActivity activity;
    private final FirebaseAuthFlow flow;

    private FirebaseAuthBridge(BrowserActivity activity, FirebaseAuthFlow flow) { this.activity = activity; this.flow = flow; }

    static FirebaseAuthBridge capture(BrowserActivity activity, String entryUrl) {
        FirebaseAuthFlow flow = FirebaseAuthFlow.capture(entryUrl);
        if (flow == null || flow.packageName().equals(activity.getPackageName())) return null;
        return installedReceiverMatches(activity, flow) ? new FirebaseAuthBridge(activity, flow) : null;
    }

    private static boolean installedReceiverMatches(BrowserActivity activity, FirebaseAuthFlow flow) {
        try {
            ComponentName receiver = new ComponentName(flow.packageName(), flow.activityClass());
            ActivityInfo info = activity.getPackageManager().getActivityInfo(receiver, 0);
            if (!info.exported) return false;
            PackageInfo client = activity.getPackageManager().getPackageInfo(flow.packageName(), PackageManager.GET_SIGNING_CERTIFICATES);
            if (client.signingInfo == null) return false;
            Signature[] certificates = client.signingInfo.hasMultipleSigners()
                    ? client.signingInfo.getApkContentsSigners() : client.signingInfo.getSigningCertificateHistory();
            for (Signature certificate : certificates) {
                byte[] digest = MessageDigest.getInstance("SHA-1").digest(certificate.toByteArray());
                StringBuilder fingerprint = new StringBuilder();
                for (byte value : digest) fingerprint.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
                if (flow.certificateSha1().equalsIgnoreCase(fingerprint.toString())) return true;
            }
        } catch (PackageManager.NameNotFoundException | java.security.NoSuchAlgorithmException | SecurityException ignored) { /* No unverified fallback. */ }
        return false;
    }

    boolean isActive() { return flow.isActive(); }

    boolean handle(String raw) {
        if (!flow.isActive() || raw.length() > 32768) return false;
        try {
            Intent returned = Intent.parseUri(raw, Intent.URI_INTENT_SCHEME);
            if (returned.getData() == null) return false;
            Uri data = returned.getData();
            if (!data.isHierarchical() || !"firebase.auth".equals(data.getHost())) return false;
            String event = returned.getStringExtra("eventId");
            String link = returned.getStringExtra("link");
            String error = returned.getStringExtra("firebaseError");
            if (event == null) event = data.getQueryParameter("eventId");
            if (link == null) link = data.getQueryParameter("link");
            if (error == null) error = data.getQueryParameter("firebaseError");
            if (returned.getPackage() != null && !flow.packageName().equals(returned.getPackage())) return false;
            if (!flow.matches(data.toString(), event, link, error)) return false;
            if (!installedReceiverMatches(activity, flow)) return false;
            // Never preserve a component, selector, fallback URL, arbitrary extras or flags from the web.
            Intent callback = new Intent(Intent.ACTION_VIEW, Uri.parse(flow.callbackUri()))
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                    .setComponent(new ComponentName(flow.packageName(), flow.activityClass()))
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra("eventId", event)
                    .putExtra("encryptionEnabled", returned.getBooleanExtra("encryptionEnabled", true));
            if (link != null) callback.putExtra("link", link);
            if (error != null) callback.putExtra("firebaseError", error);
            activity.startActivity(callback);
            flow.consume();
            activity.finish();
            return true;
        } catch (java.net.URISyntaxException | IllegalArgumentException | android.content.ActivityNotFoundException | SecurityException ignored) {
            Toast.makeText(activity, R.string.app_not_found, Toast.LENGTH_LONG).show();
            return false;
        }
    }
}
