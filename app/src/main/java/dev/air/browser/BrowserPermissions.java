package dev.air.browser;

import android.Manifest;
import android.content.pm.PackageManager;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import dev.air.navigation.WebAddress;
import java.util.Arrays;
import java.util.Set;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSession.PermissionDelegate;

final class BrowserPermissions implements PermissionDelegate {
    private static final Set<String> SUPPORTED = Set.of(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION);
    private final BrowserActivity activity;
    private final ActivityResultLauncher<String[]> launcher;
    private Callback pending;
    private AlertDialog dialog;

    BrowserPermissions(BrowserActivity activity) {
        this.activity = activity;
        launcher = activity.registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), returned -> {
            Callback callback = pending;
            pending = null;
            if (callback == null) return;
            if (!returned.isEmpty() && returned.values().stream().allMatch(Boolean.TRUE::equals)) callback.grant();
            else callback.reject();
        });
    }

    @Override public void onAndroidPermissionsRequest(@NonNull GeckoSession session, @Nullable String[] requested, @NonNull Callback callback) {
        if (requested == null || requested.length == 0 || pending != null
                || !Arrays.stream(requested).allMatch(SUPPORTED::contains)) { callback.reject(); return; }
        if (Arrays.stream(requested).allMatch(this::granted)) { callback.grant(); return; }
        pending = callback;
        launcher.launch(requested);
    }

    @Override public @Nullable GeckoResult<Integer> onContentPermissionRequest(@NonNull GeckoSession session, @NonNull ContentPermission permission) {
        if (permission.permission == PERMISSION_STORAGE_ACCESS) {
            return GeckoResult.fromValue(activity.isAuthenticating() ? ContentPermission.VALUE_ALLOW : ContentPermission.VALUE_DENY);
        }
        // Autoplay, notifications, tracker exceptions and persistent-storage grants stay disabled.
        if (permission.permission != PERMISSION_GEOLOCATION) return GeckoResult.fromValue(ContentPermission.VALUE_DENY);
        if (dialog != null && dialog.isShowing()) return GeckoResult.fromValue(ContentPermission.VALUE_DENY);
        GeckoResult<Integer> result = new GeckoResult<>();
        dialog = new AlertDialog.Builder(activity).setTitle(requestingHost(permission.uri))
                .setMessage(R.string.location_request)
                .setPositiveButton(R.string.allow, (d, which) -> result.complete(ContentPermission.VALUE_ALLOW))
                .setNegativeButton(R.string.cancel, (d, which) -> result.complete(ContentPermission.VALUE_DENY))
                .setOnCancelListener(d -> result.complete(ContentPermission.VALUE_DENY)).create();
        dialog.show();
        return result;
    }

    @Override public void onMediaPermissionRequest(@NonNull GeckoSession session, @NonNull String uri,
            @Nullable MediaSource[] video, @Nullable MediaSource[] audio, @NonNull MediaCallback callback) {
        if (dialog != null && dialog.isShowing()) { callback.reject(); return; }
        if ((video != null && !granted(Manifest.permission.CAMERA))
                || (audio != null && !granted(Manifest.permission.RECORD_AUDIO))) { callback.reject(); return; }
        MediaSource camera = video == null ? null : Arrays.stream(video).filter(source -> source.source == MediaSource.SOURCE_CAMERA).findFirst().orElse(null);
        MediaSource microphone = audio == null ? null : Arrays.stream(audio).filter(source -> source.source == MediaSource.SOURCE_MICROPHONE).findFirst().orElse(null);
        if ((video != null && camera == null) || (audio != null && microphone == null)) { callback.reject(); return; }
        dialog = new AlertDialog.Builder(activity).setTitle(requestingHost(uri))
                .setMessage(camera != null && microphone != null ? R.string.camera_microphone_request
                        : camera != null ? R.string.camera_request : R.string.microphone_request)
                .setPositiveButton(R.string.allow, (d, which) -> callback.grant(camera, microphone))
                .setNegativeButton(R.string.cancel, (d, which) -> callback.reject())
                .setOnCancelListener(d -> callback.reject()).create();
        dialog.show();
    }

    private boolean granted(String permission) { return ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED; }

    private String requestingHost(String uri) {
        WebAddress address = WebAddress.parse(uri);
        return address == null ? activity.getString(R.string.website_prompt) : address.host();
    }

    void destroy() {
        if (pending != null) pending.reject();
        pending = null;
        if (dialog != null && dialog.isShowing()) dialog.cancel();
    }
}
