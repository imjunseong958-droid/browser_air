package dev.air.browser;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.webkit.MimeTypeMap;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.mozilla.geckoview.WebResponse;

/** Saves the engine's existing response stream, preserving authenticated downloads. */
final class BrowserDownloads {
    private static final long MAX_BYTES = 100L * 1024 * 1024;
    private final BrowserActivity activity;
    private final ActivityResultLauncher<Intent> picker;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private WebResponse pending;
    private volatile InputStream reading;
    private AlertDialog confirmation;

    BrowserDownloads(BrowserActivity activity) {
        this.activity = activity;
        picker = activity.registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            WebResponse response = pending;
            pending = null;
            if (response == null || response.body == null) return;
            Uri destination = result.getData() == null ? null : result.getData().getData();
            if (result.getResultCode() != android.app.Activity.RESULT_OK || destination == null) { close(response.body); return; }
            reading = response.body;
            worker.execute(() -> {
                boolean saved = false;
                try (InputStream input = response.body;
                     OutputStream output = activity.getContentResolver().openOutputStream(destination, "w")) {
                    if (output == null) throw new java.io.IOException("No destination");
                    byte[] buffer = new byte[32 * 1024];
                    long total = 0;
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        total += count;
                        if (total > MAX_BYTES) throw new java.io.IOException("Download limit");
                        output.write(buffer, 0, count);
                    }
                    saved = true;
                } catch (java.io.IOException ignored) {
                    try { android.provider.DocumentsContract.deleteDocument(activity.getContentResolver(), destination); }
                    catch (Exception ignoredDelete) { /* Not every destination supports deletion. */ }
                } finally { reading = null; }
                boolean completed = saved;
                activity.runOnUiThread(() -> {
                    if (!activity.isFinishing()) Toast.makeText(activity, completed ? R.string.download_saved : R.string.download_failed, Toast.LENGTH_LONG).show();
                });
            });
        });
    }

    void offer(WebResponse response) {
        if (response.body == null || response.statusCode < 200 || response.statusCode >= 300) {
            close(response.body);
            Toast.makeText(activity, R.string.download_failed, Toast.LENGTH_LONG).show();
            return;
        }
        if (pending != null || reading != null) { close(response.body); return; }
        pending = response;
        String name = filename(response);
        confirmation = new AlertDialog.Builder(activity)
                .setTitle(R.string.download_file).setMessage(name)
                .setPositiveButton(R.string.save, (dialog, which) -> {
                    String type = response.headers.get("Content-Type");
                    if (type == null) type = response.headers.get("content-type");
                    if (type == null) type = "application/octet-stream";
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                            .addCategory(Intent.CATEGORY_OPENABLE).setType(type.split(";", 2)[0])
                            .putExtra(Intent.EXTRA_TITLE, name);
                    try { picker.launch(intent); }
                    catch (ActivityNotFoundException ignored) { close(response.body); pending = null; }
                })
                .setNegativeButton(R.string.cancel, (dialog, which) -> { close(response.body); pending = null; })
                .setOnCancelListener(dialog -> { close(response.body); pending = null; }).create();
        confirmation.show();
    }

    private static String filename(WebResponse response) {
        String last = Uri.parse(response.uri).getLastPathSegment();
        if (last == null || last.isBlank()) last = "document";
        String safe = last.replaceAll("[^\\p{L}\\p{N}._ -]", "_");
        if (safe.length() > 100) safe = safe.substring(0, 100);
        String type = response.headers.get("Content-Type");
        if (type == null) type = response.headers.get("content-type");
        if (!safe.contains(".") && type != null) {
            String extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(type.split(";", 2)[0]);
            if (extension != null) safe += "." + extension;
        }
        return safe;
    }

    void destroy() {
        if (confirmation != null) confirmation.dismiss();
        if (pending != null) close(pending.body);
        close(reading);
        pending = null;
        worker.shutdownNow();
    }
    private static void close(InputStream stream) { if (stream != null) try { stream.close(); } catch (java.io.IOException ignored) {} }
}
