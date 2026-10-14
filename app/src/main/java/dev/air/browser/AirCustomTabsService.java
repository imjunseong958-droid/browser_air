package dev.air.browser;

import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.browser.customtabs.CustomTabsService;
import androidx.browser.customtabs.CustomTabsSessionToken;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Lets AppAuth and other Android clients use AIR when it is the default browser. */
public final class AirCustomTabsService extends CustomTabsService {
    private static final Map<CustomTabsSessionToken, String[]> CALLERS = new ConcurrentHashMap<>();

    static String[] callerPackages(CustomTabsSessionToken token) {
        return token == null ? new String[0] : CALLERS.getOrDefault(token, new String[0]);
    }

    @Override protected boolean warmup(long flags) {
        new Handler(Looper.getMainLooper()).post(() -> ((AirApplication) getApplication()).runtime());
        return true;
    }

    @Override protected boolean newSession(@NonNull CustomTabsSessionToken token) {
        String[] packages = getPackageManager().getPackagesForUid(Binder.getCallingUid());
        if (packages == null || packages.length == 0) return false;
        if (CALLERS.size() >= 64) return false;
        CALLERS.put(token, packages);
        return true;
    }

    @Override protected boolean cleanUpSession(@NonNull CustomTabsSessionToken token) {
        CALLERS.remove(token);
        return super.cleanUpSession(token);
    }

    @Override protected boolean mayLaunchUrl(@NonNull CustomTabsSessionToken token, @Nullable Uri uri,
                                            @Nullable Bundle extras, @Nullable List<Bundle> others) {
        // Do not prefetch private login URLs or spend network/battery on unvisited links.
        return false;
    }
    @Override protected @Nullable Bundle extraCommand(@NonNull String command, @Nullable Bundle args) { return null; }
    @Override protected boolean updateVisuals(@NonNull CustomTabsSessionToken token, @Nullable Bundle bundle) { return false; }
    @Override protected boolean requestPostMessageChannel(@NonNull CustomTabsSessionToken token, @NonNull Uri origin) { return false; }
    @Override protected int postMessage(@NonNull CustomTabsSessionToken token, @NonNull String message, @Nullable Bundle extras) { return RESULT_FAILURE_DISALLOWED; }
    @Override protected boolean validateRelationship(@NonNull CustomTabsSessionToken token, int relation, @NonNull Uri origin, @Nullable Bundle extras) { return false; }
    @Override protected boolean receiveFile(@NonNull CustomTabsSessionToken token, @NonNull Uri uri, int purpose, @Nullable Bundle extras) { return false; }
}
