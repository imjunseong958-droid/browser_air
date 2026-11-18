package dev.air.browser;

import android.app.role.RoleManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDialog;
import dev.air.browser.databinding.ActivityHomeBinding;

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
        // The search card's keyboard must not squeeze the home screen behind it, so home ignores the keyboard.
        AirInsets.apply(this, ui.root, false);
        ui.poster.setClipToOutline(true);
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
        // A long verse steps down one size so it still fits under the poster on a typical phone.
        boolean longVerse = verse.ko().length() > 60;
        setSize(ui.verseKorean, longVerse ? 20 : 24, longVerse ? 32 : 36);
        setSize(ui.verseEnglish, longVerse ? 14 : 15, longVerse ? 20 : 22);
        ui.verseEnglish.setText(verse.en());
        ui.verseEnglishReference.setText(verse.referenceEn());
        ui.verse.setAlpha(0f);
        ui.verse.animate().alpha(1f).setDuration(450).start();
    }

    private static void setSize(TextView text, float sizeSp, float lineHeightSp) {
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        text.setLineHeight(Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, lineHeightSp, text.getResources().getDisplayMetrics())));
    }

    private void showSearch() {
        AppCompatDialog dialog = SearchCard.show(this, url -> {
            returningFromBrowser = true;
            startActivity(new Intent(this, BrowserActivity.class).setAction(Intent.ACTION_VIEW).setData(Uri.parse(url)));
            return true;
        });
        // The search box moves up into the card, so the home search bar steps aside until the card closes.
        ui.searchButton.animate().cancel();
        ui.searchButton.animate().alpha(0f).setDuration(150).withEndAction(() -> ui.searchButton.setVisibility(View.INVISIBLE)).start();
        dialog.setOnDismissListener(closed -> {
            ui.searchButton.animate().cancel();
            ui.searchButton.setVisibility(View.VISIBLE);
            ui.searchButton.animate().alpha(1f).setDuration(200).start();
        });
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
