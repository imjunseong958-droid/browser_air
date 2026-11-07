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
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDialog;
import dev.air.browser.databinding.ActivityHomeBinding;
import dev.air.browser.databinding.DialogSearchBinding;
import dev.air.navigation.NavigationPolicy;

public final class HomeActivity extends AppCompatActivity {
    private static final String SHOWN_VERSE = "shown_verse";
    private ActivityResultLauncher<Intent> roleLauncher;
    private ActivityHomeBinding ui;
    private HomeUpdates updates;
    private Verses verses;
    private int shownVerse;
    private boolean returningFromBrowser;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        roleLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {});
        ui = ActivityHomeBinding.inflate(getLayoutInflater());
        setContentView(ui.getRoot());
        AirInsets.apply(this, ui.root);
        ui.searchButton.setOnClickListener(view -> showSearch());
        updates = new HomeUpdates(this, ui.updateButton);
        verses = new Verses(this);
        // Turning the screen keeps the verse; opening the app shows the next one.
        showVerse(state == null ? verses.next() : state.getInt(SHOWN_VERSE));
        if (state == null && !getPreferences(MODE_PRIVATE).getBoolean("setup_seen", false)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("setup_seen", true).apply();
            ui.root.post(this::showSetup);
        }
    }

    /** Coming back to AIR from elsewhere counts as opening it; closing a page opened from here does not. */
    @Override protected void onRestart() {
        super.onRestart();
        if (!returningFromBrowser) showVerse(verses.next());
        returningFromBrowser = false;
    }

    @Override protected void onSaveInstanceState(@NonNull Bundle state) {
        super.onSaveInstanceState(state);
        state.putInt(SHOWN_VERSE, shownVerse);
    }

    @Override protected void onResume() {
        super.onResume();
        RoleManager roles = getSystemService(RoleManager.class);
        ui.linkHint.setVisibility(roles != null && roles.isRoleHeld(RoleManager.ROLE_BROWSER) ? View.GONE : View.VISIBLE);
        updates.resume();
    }

    private void showVerse(int index) {
        shownVerse = index;
        Verses.Verse verse = verses.get(index);
        ui.verseReference.setText(verse.referenceKo());
        // Word joiners keep each Korean word whole at a line's end on every Android version and device language.
        ui.verseKorean.setText(verse.ko().replaceAll("(?<=\\S)(?=\\S)", "⁠"));
        ui.verseKorean.setContentDescription(verse.ko());
        ui.verseEnglish.setText(verse.en());
        ui.verseEnglishReference.setText(verse.referenceEn());
        ui.verse.setAlpha(0f);
        ui.verse.animate().alpha(1f).setDuration(450).start();
    }

    /** One typed query opens Google's SafeSearch text results; there is no suggestion list or history. */
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
            }
        });
        search.clearButton.setOnClickListener(view -> search.terms.setText(""));
        search.googleButton.setOnClickListener(view -> {
            if (openSearch(search.terms.getText().toString())) dialog.dismiss();
        });
        search.terms.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            if (openSearch(search.terms.getText().toString())) dialog.dismiss();
            return true;
        });
        dialog.show();
        search.terms.requestFocus();
    }

    private boolean openSearch(String terms) {
        String url = NavigationPolicy.searchUrl(terms);
        if (url == null) return false;
        returningFromBrowser = true;
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
