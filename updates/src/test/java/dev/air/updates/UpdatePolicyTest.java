package dev.air.updates;

import static org.junit.Assert.*;
import java.net.URI;
import org.junit.Test;

public final class UpdatePolicyTest {
    private static final String HASH = "a".repeat(64);
    private static UpdateInfo info(long code, String url, String hash, long size) {
        return new UpdateInfo(code, "0.1.2+1009", 29, url, hash, size);
    }
    @Test public void onlyStrictlyNewerVersionsAreUpdates() {
        UpdateInfo update = info(1009, UpdateInfo.apkUrl(1009), HASH, 123);
        assertTrue(update.isNewerThan(1008));
        assertFalse(update.isNewerThan(1009));
        assertFalse(update.isNewerThan(1010));
    }
    @Test public void downloadIsPinnedToThisRepositoryAndVersion() {
        for (String url : new String[]{"http://github.com/imjunseong958-droid/browser_air/releases/download/air-v1009/AIR.apk",
                UpdateInfo.apkUrl(1008), UpdateInfo.apkUrl(1009) + "?other=1",
                UpdateInfo.apkUrl(1009).replace("browser_air", "other"), "https://example.com/AIR.apk"}) {
            assertThrows(IllegalArgumentException.class, () -> info(1009, url, HASH, 123));
        }
    }
    @Test public void malformedChecksumsAndOversizedApksAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> info(1009, UpdateInfo.apkUrl(1009), "z".repeat(64), 123));
        assertThrows(IllegalArgumentException.class, () -> info(1009, UpdateInfo.apkUrl(1009), HASH, UpdateInfo.MAX_APK_BYTES + 1));
        assertThrows(IllegalArgumentException.class, () -> info(1009, UpdateInfo.apkUrl(1009), HASH, 0));
    }
    @Test public void invalidVersionCodesAreRejected() {
        for (long code : new long[]{0, -1, 2_100_000_001L, Long.MAX_VALUE})
            assertThrows(IllegalArgumentException.class, () -> info(code, UpdateInfo.apkUrl(code), HASH, 123));
    }
    @Test public void checksAreDailyAndRecoverFromClockChanges() {
        long now = 1_800_000_000_000L;
        assertTrue(UpdateInfo.checkIsDue(now, 0));
        assertFalse(UpdateInfo.checkIsDue(now, now - 86_399_999));
        assertTrue(UpdateInfo.checkIsDue(now, now - 86_400_000));
        assertTrue(UpdateInfo.checkIsDue(now, now + 1000));
    }
    @Test public void redirectsCanUseGithubReleaseAssetHosts() {
        for (String url : new String[]{UpdateInfo.MANIFEST_URL, UpdateInfo.apkUrl(1009),
                UpdateInfo.RELEASE_BASE + "download/air-v1009/update.json",
                "https://release-assets.githubusercontent.com/asset?signature=fixture",
                "https://objects.githubusercontent.com/asset"})
            assertTrue(url, GitHubUpdateTransport.isAllowedUrl(URI.create(url)));
    }
    @Test public void redirectsCannotEscapeToHttpOtherRepositoriesOrLookalikeHosts() {
        for (String url : new String[]{"http://release-assets.githubusercontent.com/asset",
                "https://github.com.attacker.invalid/asset", "https://github.com@attacker.invalid/asset",
                "https://attacker@github.com/imjunseong958-droid/browser_air/releases/latest/download/update.json",
                "https://github.com:444/imjunseong958-droid/browser_air/releases/latest/download/update.json",
                UpdateInfo.apkUrl(1009).replace("browser_air", "other"), UpdateInfo.MANIFEST_URL + "#fragment",
                "https://github.com/imjunseong958-droid/browser_air/releases/download/air-v1009/%41IR.apk"})
            assertFalse(url, GitHubUpdateTransport.isAllowedUrl(URI.create(url)));
    }
}
