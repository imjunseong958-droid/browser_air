package dev.air.browser;

import android.app.Application;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import dev.air.updates.GitHubUpdateTransport;
import dev.air.updates.UpdateInfo;
import dev.air.updates.UpdateTransfer;
import dev.air.updates.UpdateTransport;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Network work survives screen rotation and never retains an Activity. */
public final class AirUpdateModel extends AndroidViewModel {
    enum Phase { IDLE, CHECKING, DOWNLOADING, READY }
    static final class State {
        final UpdateInfo available;
        final Phase phase;
        final int progress;
        State(UpdateInfo available, Phase phase, int progress) {
            this.available = available; this.phase = phase; this.progress = progress;
        }
    }
    static final String PREFS = "air_updates";
    private final SharedPreferences preferences;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy = new AtomicBoolean();
    private final MutableLiveData<State> state = new MutableLiveData<>();
    private final UpdateTransport transport = new GitHubUpdateTransport();
    private volatile boolean cleared;
    private volatile boolean installRequested;
    private volatile File installFile;
    private volatile int notice;
    private volatile UpdateInfo available;

    public AirUpdateModel(Application app) {
        super(app);
        preferences = app.getSharedPreferences(PREFS, Application.MODE_PRIVATE);
        try {
            String cached = preferences.getString("manifest", null);
            if (cached != null) {
                UpdateInfo info = UpdateManifest.read(cached.getBytes(StandardCharsets.UTF_8));
                if (info.isNewerThan(installedVersion()) && info.minSdk <= Build.VERSION.SDK_INT) available = info;
                else preferences.edit().remove("manifest").apply();
            }
        } catch (Exception invalidCache) { preferences.edit().remove("manifest").apply(); }
        state.setValue(new State(available, Phase.IDLE, 0));
        if (available == null) worker.execute(() -> removeOtherDownloads(null));
    }

    LiveData<State> state() { return state; }
    UpdateInfo available() { return available; }
    boolean isBusy() { return busy.get(); }
    File candidate(UpdateInfo info) { return new File(getApplication().getCacheDir(), "air-updates/AIR-" + info.versionCode + ".apk"); }

    void check(boolean manual) {
        if (cleared || installRequested || awaitingPermission()) return;
        long now = System.currentTimeMillis();
        // Debug signing keys belong to a different update channel.
        if (!manual && ((getApplication().getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0
                || !UpdateInfo.checkIsDue(now, preferences.getLong("last_attempt", 0)))) return;
        if (!busy.compareAndSet(false, true)) return;
        preferences.edit().putLong("last_attempt", now).apply();
        state.setValue(new State(available, Phase.CHECKING, 0));
        worker.execute(() -> {
            try {
                byte[] bytes = UpdateTransfer.readManifest(transport);
                UpdateInfo info = UpdateManifest.read(bytes);
                if (info.isNewerThan(installedVersion()) && info.minSdk <= Build.VERSION.SDK_INT) {
                    available = info;
                    preferences.edit().putString("manifest", new String(bytes, StandardCharsets.UTF_8)).apply();
                    removeOtherDownloads(candidate(info));
                    if (manual) notice = R.string.update_found;
                } else {
                    available = null;
                    preferences.edit().remove("manifest").apply();
                    removeOtherDownloads(null);
                    if (manual) notice = info.minSdk > Build.VERSION.SDK_INT ? R.string.update_unsupported : R.string.update_latest;
                }
            } catch (UpdateTransport.HttpFailure error) {
                if (manual) notice = error.status == 404 ? R.string.update_not_published : R.string.update_check_failed;
            } catch (Exception error) {
                if (manual) notice = R.string.update_check_failed;
            } finally {
                busy.set(false);
                if (!cleared) state.postValue(new State(available, Phase.IDLE, 0));
            }
        });
    }

    void downloadForInstall() {
        if (cleared) return;
        UpdateInfo info = available;
        if (info == null || !busy.compareAndSet(false, true)) return;
        installRequested = false;
        state.setValue(new State(info, Phase.DOWNLOADING, 0));
        worker.execute(() -> {
            File apk = candidate(info);
            boolean ready = false;
            try {
                if (!UpdateTransfer.matches(apk, info)) {
                    final int[] lastPercent = {-1};
                    UpdateTransfer.download(transport, info, apk, (bytes, total) -> {
                        int percent = (int) (bytes * 100 / total);
                        if (percent != lastPercent[0] && !cleared) {
                            lastPercent[0] = percent;
                            state.postValue(new State(info, Phase.DOWNLOADING, percent));
                        }
                    });
                }
                try { UpdateApk.verify(getApplication(), apk, info); }
                catch (Exception rejected) {
                    apk.delete();
                    notice = R.string.update_verify_failed;
                    return;
                }
                ready = true;
                if (!cleared) { installFile = apk; installRequested = true; }
            } catch (IOException error) {
                notice = R.string.update_download_failed;
            } finally {
                busy.set(false);
                if (!cleared) state.postValue(new State(info, ready ? Phase.READY : Phase.IDLE, 0));
            }
        });
    }

    File consumeInstallRequest() {
        if (!installRequested) return null;
        installRequested = false;
        return installFile;
    }
    int consumeNotice() { int message = notice; notice = 0; return message; }

    void awaitingPermission(boolean waiting) {
        preferences.edit().putLong("permission_request", waiting ? System.currentTimeMillis() : 0).apply();
    }
    boolean awaitingPermission() {
        long requested = preferences.getLong("permission_request", 0);
        long elapsed = System.currentTimeMillis() - requested;
        return available != null && requested > 0 && elapsed >= 0 && elapsed < 15 * 60_000L;
    }

    private long installedVersion() throws android.content.pm.PackageManager.NameNotFoundException {
        return getApplication().getPackageManager().getPackageInfo(getApplication().getPackageName(), 0).getLongVersionCode();
    }
    private void removeOtherDownloads(File keep) {
        File directory = new File(getApplication().getCacheDir(), "air-updates");
        File[] files = directory.listFiles();
        if (files != null) for (File file : files) {
            boolean completed = file.getName().matches("AIR-[0-9]+\\.apk");
            boolean abandoned = file.getName().endsWith(".part") && System.currentTimeMillis() - file.lastModified() > 86_400_000L;
            if (!file.equals(keep) && (completed || abandoned)) file.delete();
        }
    }
    @Override protected void onCleared() {
        cleared = true;
        worker.shutdownNow();
    }
}
