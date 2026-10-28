package dev.air.browser;

import android.app.role.RoleManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDialog;
import dev.air.browser.databinding.ActivityHomeBinding;
import dev.air.browser.databinding.DialogSearchBinding;
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
        ui.linkHint.setVisibility(roles != null && roles.isRoleHeld(RoleManager.ROLE_BROWSER) ? View.GONE : View.VISIBLE);
        updates.resume();
    }

    /** One typed query opens Google's SafeSearch text results or YouTube results; there is no suggestion list or history. */
    private void showSearch() {
        DialogSearchBinding search = DialogSearchBinding.inflate(getLayoutInflater());
        AppCompatDialog dialog = new AppCompatDialog(this, R.style.Air_Spotlight);
        dialog.setContentView(search.getRoot());
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
                    | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
                WindowManager.LayoutParams attributes = window.getAttributes();
                attributes.setBlurBehindRadius(Math.round(28 * getResources().getDisplayMetrics().density));
                window.setAttributes(attributes);
            }
        }
        search.root.setOnClickListener(view -> dialog.dismiss());
        search.terms.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable text) {
                String terms = text.toString().strip();
                search.results.setVisibility(terms.isEmpty() ? View.GONE : View.VISIBLE);
                search.clearButton.setVisibility(text.length() > 0 ? View.VISIBLE : View.GONE);
                search.googleQuery.setText(terms);
                search.youtubeQuery.setText(terms);
            }
        });
        search.clearButton.setOnClickListener(view -> search.terms.setText(""));
        search.googleButton.setOnClickListener(view -> {
            if (openSearch(NavigationPolicy.searchUrl(search.terms.getText().toString()))) dialog.dismiss();
        });
        search.youtubeButton.setOnClickListener(view -> {
            if (openSearch(NavigationPolicy.youtubeSearchUrl(search.terms.getText().toString()))) dialog.dismiss();
        });
        search.terms.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            if (openSearch(NavigationPolicy.searchUrl(search.terms.getText().toString()))) dialog.dismiss();
            return true;
        });
        dialog.show();
        search.terms.requestFocus();
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
