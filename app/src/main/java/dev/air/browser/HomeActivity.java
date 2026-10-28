package dev.air.browser;

import android.app.role.RoleManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import dev.air.browser.databinding.ActivityHomeBinding;
import dev.air.navigation.NavigationPolicy;

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
        ui.searchButton.setOnClickListener(view -> showSearch());
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

    /** One typed query opens Google's SafeSearch text results or YouTube results; there is no suggestion list or history. */
    private void showSearch() {
        EditText terms = new EditText(this);
        terms.setHint(R.string.search_hint);
        terms.setSingleLine(true);
        terms.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        terms.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        terms.setFilters(new InputFilter[] {new InputFilter.LengthFilter(200)});
        FrameLayout frame = new FrameLayout(this);
        int inset = Math.round(20 * getResources().getDisplayMetrics().density);
        frame.setPadding(inset, 0, inset, 0);
        frame.addView(terms);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.search_title)
                .setMessage(R.string.search_body)
                .setView(frame)
                .setPositiveButton(R.string.search_action,
                        (shown, which) -> openSearch(NavigationPolicy.searchUrl(terms.getText().toString())))
                .setNeutralButton(R.string.search_youtube,
                        (shown, which) -> openSearch(NavigationPolicy.youtubeSearchUrl(terms.getText().toString())))
                .setNegativeButton(R.string.cancel, null)
                .create();
        terms.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            if (openSearch(NavigationPolicy.searchUrl(terms.getText().toString()))) dialog.dismiss();
            return true;
        });
        if (dialog.getWindow() != null) dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        dialog.show();
        for (int button : new int[] {AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEUTRAL, AlertDialog.BUTTON_NEGATIVE}) {
            dialog.getButton(button).setAllCaps(false);
        }
        terms.requestFocus();
    }

    private boolean openSearch(String url) {
        if (url == null) return false;
        startActivity(new Intent(this, BrowserActivity.class).setAction(Intent.ACTION_VIEW).setData(Uri.parse(url)));
        return true;
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
