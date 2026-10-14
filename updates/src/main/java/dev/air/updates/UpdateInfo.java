package dev.air.updates;

/** The release manifest is data, never an instruction to install an arbitrary URL. */
public final class UpdateInfo {
    public static final String APPLICATION_ID = "dev.air.browser";
    public static final String RELEASE_BASE = "https://github.com/imjunseong958-droid/browser_air/releases/";
    public static final String MANIFEST_URL = RELEASE_BASE + "latest/download/update.json";
    public static final long MAX_APK_BYTES = 384L * 1024 * 1024;

    public final long versionCode;
    public final String versionName;
    public final int minSdk;
    public final String apkUrl;
    public final String sha256;
    public final long sizeBytes;

    public UpdateInfo(long versionCode, String versionName, int minSdk, String apkUrl, String sha256, long sizeBytes) {
        if (versionCode < 1 || versionCode > 2_100_000_000L
                || versionName == null || !versionName.matches("[0-9A-Za-z.+_-]{1,64}")
                || minSdk < 29 || minSdk > 10_000
                || !apkUrl(versionCode).equals(apkUrl)
                || sha256 == null || !sha256.matches("[0-9a-f]{64}")
                || sizeBytes < 1 || sizeBytes > MAX_APK_BYTES) {
            throw new IllegalArgumentException("Invalid AIR release manifest");
        }
        this.versionCode = versionCode;
        this.versionName = versionName;
        this.minSdk = minSdk;
        this.apkUrl = apkUrl;
        this.sha256 = sha256;
        this.sizeBytes = sizeBytes;
    }

    public static String apkUrl(long versionCode) {
        return RELEASE_BASE + "download/air-v" + versionCode + "/AIR.apk";
    }

    public boolean isNewerThan(long installedVersion) { return versionCode > installedVersion; }

    /** Handles a device clock moving backwards without suppressing checks forever. */
    public static boolean checkIsDue(long now, long lastAttempt) {
        return lastAttempt <= 0 || now < lastAttempt || now - lastAttempt >= 86_400_000L;
    }
}
