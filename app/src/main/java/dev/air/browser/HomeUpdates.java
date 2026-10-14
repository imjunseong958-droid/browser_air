package dev.air.browser;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.AppCompatButton;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.ViewModelProvider;
import dev.air.updates.UpdateInfo;
import java.io.File;

final class HomeUpdates {
    private final HomeActivity activity;
    private final AppCompatButton button;
    private final AirUpdateModel model;
    private final ActivityResultLauncher<Intent> permissions;
    private final ActivityResultLauncher<Intent> installer;

    HomeUpdates(HomeActivity activity, AppCompatButton button) {
        this.activity = activity;
        this.button = button;
        model = new ViewModelProvider(activity).get(AirUpdateModel.class);
        permissions = activity.registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (!model.awaitingPermission()) return;
            model.awaitingPermission(false);
            if (activity.getPackageManager().canRequestPackageInstalls()) model.downloadForInstall();
            else Toast.makeText(activity, R.string.update_permission_needed, Toast.LENGTH_LONG).show();
        });
        installer = activity.registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> resume());
        model.state().observe(activity, this::render);
        button.setOnClickListener(view -> {
            UpdateInfo info = model.available();
            if (info == null) { model.check(true); return; }
            new AlertDialog.Builder(activity)
                    .setTitle(R.string.update_title)
                    .setMessage(activity.getString(R.string.update_body, info.versionName,
                            (int) Math.ceil(info.sizeBytes / (1024.0 * 1024.0))))
                    .setPositiveButton(R.string.update_download, (dialog, which) -> model.downloadForInstall())
                    .setNegativeButton(R.string.later, null)
                    .show();
        });
    }

    void resume() {
        if (model.awaitingPermission() && activity.getPackageManager().canRequestPackageInstalls()) {
            model.awaitingPermission(false);
            model.downloadForInstall();
        }
        model.check(false);
        render(model.state().getValue());
    }

    private void render(AirUpdateModel.State state) {
        if (state == null) return;
        boolean busy = state.phase == AirUpdateModel.Phase.CHECKING || state.phase == AirUpdateModel.Phase.DOWNLOADING;
        button.setEnabled(!busy);
        if (state.phase == AirUpdateModel.Phase.CHECKING) button.setText(R.string.update_checking);
        else if (state.phase == AirUpdateModel.Phase.DOWNLOADING) button.setText(activity.getString(R.string.update_progress, state.progress));
        else if (state.available != null) button.setText(activity.getString(R.string.update_available, state.available.versionName));
        else button.setText(R.string.update_check);
        if (!activity.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)) return;
        int notice = model.consumeNotice();
        if (notice != 0) Toast.makeText(activity, notice, Toast.LENGTH_LONG).show();
        File apk = model.consumeInstallRequest();
        if (apk == null) return;
        if (!activity.getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(activity)
                    .setTitle(R.string.update_permission_title)
                    .setMessage(R.string.update_permission_body)
                    .setPositiveButton(R.string.update_permission_action, (dialog, which) -> {
                        model.awaitingPermission(true);
                        try {
                            permissions.launch(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + activity.getPackageName())));
                        } catch (ActivityNotFoundException | SecurityException unavailable) {
                            model.awaitingPermission(false);
                            Toast.makeText(activity, R.string.update_installer_unavailable, Toast.LENGTH_LONG).show();
                        }
                    })
                    .setNegativeButton(R.string.later, null)
                    .show();
            return;
        }
        try { installer.launch(UpdateApk.installIntent(activity, apk)); }
        catch (ActivityNotFoundException | IllegalArgumentException | SecurityException unavailable) {
            Toast.makeText(activity, R.string.update_installer_unavailable, Toast.LENGTH_LONG).show();
        }
    }
}
