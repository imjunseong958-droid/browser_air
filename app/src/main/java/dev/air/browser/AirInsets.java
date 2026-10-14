package dev.air.browser;

import android.view.View;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

final class AirInsets {
    private AirInsets() {}
    static void apply(AppCompatActivity activity, View root) {
        WindowCompat.setDecorFitsSystemWindows(activity.getWindow(), false);
        WindowCompat.getInsetsController(activity.getWindow(), root).setAppearanceLightStatusBars(true);
        WindowCompat.getInsetsController(activity.getWindow(), root).setAppearanceLightNavigationBars(true);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            Insets keyboard = windowInsets.getInsets(WindowInsetsCompat.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, keyboard.bottom));
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(root);
    }
}
