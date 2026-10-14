package dev.air.browser;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import androidx.core.content.FileProvider;
import dev.air.updates.UpdateInfo;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.zip.ZipFile;

final class UpdateApk {
    private UpdateApk() {}

    static void verify(Context context, File apk, UpdateInfo info) throws IOException, PackageManager.NameNotFoundException {
        PackageManager packages = context.getPackageManager();
        PackageInfo installed = packages.getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
        PackageInfo candidate = packages.getPackageArchiveInfo(apk.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
        if (candidate == null || candidate.applicationInfo == null
                || !context.getPackageName().equals(candidate.packageName)
                || !UpdateInfo.APPLICATION_ID.equals(candidate.packageName)
                || candidate.getLongVersionCode() != info.versionCode
                || !info.isNewerThan(installed.getLongVersionCode())
                || !info.versionName.equals(candidate.versionName)
                || candidate.applicationInfo.minSdkVersion != info.minSdk
                || info.minSdk > android.os.Build.VERSION.SDK_INT
                || (candidate.applicationInfo.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0
                || !sameSigners(installed, candidate)) throw new IOException("Rejected update APK identity");
        HashSet<String> abis = new HashSet<>();
        try (ZipFile zip = new ZipFile(apk)) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (name.startsWith("lib/") && name.endsWith(".so")) {
                    String[] parts = name.split("/");
                    if (parts.length != 3) throw new IOException("Invalid native library path");
                    abis.add(parts[1]);
                }
            }
        }
        if (!abis.equals(java.util.Set.of("arm64-v8a"))) throw new IOException("Unexpected update ABI");
    }

    private static boolean sameSigners(PackageInfo installed, PackageInfo candidate) {
        if (installed.signingInfo == null || candidate.signingInfo == null) return false;
        Signature[] current = installed.signingInfo.getApkContentsSigners();
        Signature[] update = candidate.signingInfo.getApkContentsSigners();
        return current != null && current.length > 0 && update != null
                && new HashSet<>(Arrays.asList(current)).equals(new HashSet<>(Arrays.asList(update)));
    }

    static Intent installIntent(Context context, File apk) {
        return new Intent(Intent.ACTION_VIEW)
                .setDataAndType(FileProvider.getUriForFile(context, context.getPackageName() + ".updates", apk),
                        "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }
}
