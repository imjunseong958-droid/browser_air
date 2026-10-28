package dev.air.browser;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.widget.Button;
import androidx.core.content.FileProvider;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.air.updates.UpdateInfo;
import java.io.File;
import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class UpdateIntegrationTest {
    private Context app() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }
    private JSONObject manifest(long code) throws Exception {
        return new JSONObject().put("schemaVersion", 1).put("applicationId", "dev.air.browser").put("abi", "arm64-v8a")
                .put("versionCode", code).put("versionName", "0.1.3").put("minSdk", 29)
                .put("apkUrl", UpdateInfo.apkUrl(code)).put("sha256", "a".repeat(64)).put("sizeBytes", 123);
    }
    private UpdateInfo read(JSONObject json) throws Exception { return UpdateManifest.read(json.toString().getBytes(StandardCharsets.UTF_8)); }
    @After public void cleanup() { app().getSharedPreferences(AirUpdateModel.PREFS, 0).edit().clear().commit(); }
    @Test public void parserRejectsWrongSchemaPackageAbiAndNonIntegerVersions() throws Exception {
        for (String field : new String[]{"schemaVersion", "applicationId", "abi", "versionCode"}) {
            JSONObject json = manifest(1009); json.put(field, field.equals("versionCode") ? 1009.5 : "unexpected");
            assertThrows(JSONException.class, () -> read(json));
        }
        JSONObject json = manifest(1009).put("versionCode", "1009");
        assertThrows(JSONException.class, () -> read(json));
    }
    @Test public void malformedUtf8AndUnknownDownloadSourcesAreRejected() throws Exception {
        assertThrows(CharacterCodingException.class, () -> UpdateManifest.read(new byte[]{(byte) 0xc3, 0x28}));
        JSONObject json = manifest(1009).put("apkUrl", "https://example.com/AIR.apk");
        assertThrows(JSONException.class, () -> read(json));
    }
    @Test public void providerCannotExposeAppFilesOutsideItsUpdateCache() {
        File privateFile = new File(app().getFilesDir(), "update-provider-fixture");
        assertThrows(IllegalArgumentException.class, () -> FileProvider.getUriForFile(app(), app().getPackageName() + ".updates", privateFile));
    }
    @Test public void installerReceivesOnlyAContentUriAndTemporaryReadAccess() throws Exception {
        File apk = new File(app().getCacheDir(), "air-updates/provider-fixture.apk");
        apk.getParentFile().mkdirs();
        java.nio.file.Files.write(apk.toPath(), new byte[]{1, 2, 3});
        Intent intent = UpdateApk.installIntent(app(), apk);
        assertEquals(Intent.ACTION_VIEW, intent.getAction()); assertEquals("application/vnd.android.package-archive", intent.getType());
        assertEquals("content", intent.getData().getScheme()); assertEquals(app().getPackageName() + ".updates", intent.getData().getAuthority());
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, intent.getFlags());
        try (var input = app().getContentResolver().openInputStream(intent.getData())) {
            assertArrayEquals(new byte[]{1, 2, 3}, input.readAllBytes());
        } finally { apk.delete(); }
    }
    @Test public void installedAndUnrelatedApksCannotBeOfferedAsUpdates() throws Exception {
        PackageInfo installed = app().getPackageManager().getPackageInfo(app().getPackageName(), 0);
        UpdateInfo same = read(manifest(installed.getLongVersionCode()));
        assertThrows(IOException.class, () -> UpdateApk.verify(app(), new File(installed.applicationInfo.sourceDir), same));
        Context tests = InstrumentationRegistry.getInstrumentation().getContext();
        assertThrows(IOException.class, () -> UpdateApk.verify(app(), new File(tests.getApplicationInfo().sourceDir), read(manifest(installed.getLongVersionCode() + 1))));
    }
    @Test public void availableUpdateSurvivesHomeScreenRotation() throws Exception {
        long installed = app().getPackageManager().getPackageInfo(app().getPackageName(), 0).getLongVersionCode();
        app().getSharedPreferences(AirUpdateModel.PREFS, 0).edit().putString("manifest", manifest(installed + 1).toString()).commit();
        try (ActivityScenario<HomeActivity> scenario = ActivityScenario.launch(HomeActivity.class)) {
            scenario.onActivity(activity -> assertEquals("AIR 0.1.3 업데이트", ((Button) activity.findViewById(R.id.updateButton)).getText().toString()));
            scenario.recreate();
            scenario.onActivity(activity -> assertEquals("AIR 0.1.3 업데이트", ((Button) activity.findViewById(R.id.updateButton)).getText().toString()));
        }
    }
    @Test public void cacheDoesNotOfferAnAlreadyInstalledVersion() throws Exception {
        long installed = app().getPackageManager().getPackageInfo(app().getPackageName(), 0).getLongVersionCode();
        app().getSharedPreferences(AirUpdateModel.PREFS, 0).edit().putString("manifest", manifest(installed).toString())
                .putLong("last_attempt", System.currentTimeMillis()).commit();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            AirUpdateModel model = new AirUpdateModel((Application) app().getApplicationContext());
            assertNull(model.available()); model.onCleared();
        });
        // Installing the remembered update makes the next check due at once, so a newer release is found without a day's wait.
        assertFalse(app().getSharedPreferences(AirUpdateModel.PREFS, 0).contains("last_attempt"));
    }
}
