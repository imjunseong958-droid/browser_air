package dev.air.browser;

import android.app.role.RoleManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import dev.air.browser.databinding.ActivityHomeBinding;

public final class HomeActivity extends AppCompatActivity {
    private ActivityResultLauncher<Intent> roleLauncher;
    private ActivityHomeBinding ui;
    private HomeUpdates updates;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        roleLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {});
        ui = ActivityHomeBinding.inflate(getLayoutInflater());
        setContentView(ui.getRoot());
        AirInsets.apply(this, ui.root);
        ui.scanButton.setOnClickListener(view -> startActivity(new Intent(this, ScanActivity.class)));
        updates = new HomeUpdates(this, ui.updateButton);
        if (state == null && !getPreferences(MODE_PRIVATE).getBoolean("setup_seen", false)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("setup_seen", true).apply();
            ui.root.post(this::showSetup);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        RoleManager roles = getSystemService(RoleManager.class);
        ui.linkHint.setText(roles != null && roles.isRoleHeld(RoleManager.ROLE_BROWSER)
                ? R.string.home_hint : R.string.home_hint_not_default);
        updates.resume();
    }

    private void showSetup() {
        if (isFinishing()) return;
        RoleManager roles = getSystemService(RoleManager.class);
        if (roles != null && roles.isRoleHeld(RoleManager.ROLE_BROWSER)) return;
        new AlertDialog.Builder(this)
                .setTitle(R.string.setup_title)
                .setMessage(R.string.setup_body)
                .setPositiveButton(R.string.set_default, (dialog, which) -> {
                    try {
                        if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_BROWSER)) {
                            roleLauncher.launch(roles.createRequestRoleIntent(RoleManager.ROLE_BROWSER));
                        } else Toast.makeText(this, R.string.default_unavailable, Toast.LENGTH_LONG).show();
                    } catch (ActivityNotFoundException ignored) {
                        Toast.makeText(this, R.string.default_unavailable, Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton(R.string.later, null)
                .show();
    }
}
