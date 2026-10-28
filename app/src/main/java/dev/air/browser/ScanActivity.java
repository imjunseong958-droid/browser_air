package dev.air.browser;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import com.google.zxing.BarcodeFormat;
import com.journeyapps.barcodescanner.BarcodeCallback;
import com.journeyapps.barcodescanner.BarcodeResult;
import com.journeyapps.barcodescanner.DefaultDecoderFactory;
import dev.air.browser.databinding.ActivityScanBinding;
import dev.air.navigation.NavigationPolicy;
import java.util.List;

public final class ScanActivity extends AppCompatActivity {
    private ActivityScanBinding ui;
    private boolean opened;
    private boolean torch;
    private long lastRejected;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ui = ActivityScanBinding.inflate(getLayoutInflater());
        setContentView(ui.getRoot());
        AirInsets.apply(this, ui.root);
        ui.closeButton.setOnClickListener(view -> finish());
        ui.torchButton.setEnabled(getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH));
        ui.torchButton.setOnClickListener(view -> {
            torch = !torch;
            ui.barcodeView.setTorch(torch);
            ui.torchButton.setText(torch ? R.string.torch_off : R.string.torch_on);
        });
        ui.barcodeView.setDecoderFactory(new DefaultDecoderFactory(List.of(BarcodeFormat.QR_CODE)));
        ui.barcodeView.decodeContinuous(new BarcodeCallback() {
            @Override public void barcodeResult(BarcodeResult result) {
                if (opened) return;
                NavigationPolicy.Decision decision = NavigationPolicy.entryDecision(result.getText());
                if (decision != NavigationPolicy.Decision.ALLOW) {
                    long now = SystemClock.elapsedRealtime();
                    if (now - lastRejected > 2500) {
                        Toast.makeText(ScanActivity.this, decision == NavigationPolicy.Decision.BLOCK_SEARCH
                                ? R.string.search_blocked : decision == NavigationPolicy.Decision.BLOCK_COMMUNITY
                                || decision == NavigationPolicy.Decision.BLOCK_ADULT
                                ? R.string.site_blocked : R.string.invalid_link_body, Toast.LENGTH_SHORT).show();
                        lastRejected = now;
                    }
                    return;
                }
                opened = true;
                ui.barcodeView.pause();
                startActivity(new Intent(ScanActivity.this, BrowserActivity.class)
                        .setAction(Intent.ACTION_VIEW).setData(Uri.parse(result.getText())));
                finish();
            }
        });
        ActivityResultLauncher<String> permission = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {
                    if (granted) ui.barcodeView.resume(); else showPermissionHelp();
                });
        if (!hasCameraPermission()) permission.launch(Manifest.permission.CAMERA);
    }

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void showPermissionHelp() {
        new AlertDialog.Builder(this).setTitle(R.string.camera_denied).setMessage(R.string.camera_denied_body)
                .setPositiveButton(R.string.open_settings, (dialog, which) -> startActivity(
                        new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))))
                .setNegativeButton(R.string.close, (dialog, which) -> finish()).show();
    }

    @Override protected void onResume() { super.onResume(); if (!opened && hasCameraPermission()) ui.barcodeView.resume(); }
    @Override protected void onPause() { ui.barcodeView.pause(); super.onPause(); }
}
