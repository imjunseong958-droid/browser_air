package dev.air.updates;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class UpdateTransfer {
    private UpdateTransfer() {}

    public interface Progress { void received(long bytes, long total); }

    public static byte[] readManifest(UpdateTransport transport) throws IOException {
        try (UpdateTransport.Response response = transport.open(UpdateInfo.MANIFEST_URL);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (response.length() > 64 * 1024) throw new IOException("Oversized update manifest");
            byte[] buffer = new byte[4096];
            int count;
            while ((count = response.body().read(buffer)) != -1) {
                interrupted();
                if (output.size() + count > 64 * 1024) throw new IOException("Oversized update manifest");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    public static void download(UpdateTransport transport, UpdateInfo info, File destination, Progress progress) throws IOException {
        File directory = destination.getParentFile();
        if (directory == null || (!directory.isDirectory() && !directory.mkdirs())) throw new IOException("No update cache");
        File partial = File.createTempFile("AIR-", ".part", directory);
        boolean published = false;
        try {
            MessageDigest digest = sha256();
            try (UpdateTransport.Response response = transport.open(info.apkUrl);
                 FileOutputStream output = new FileOutputStream(partial)) {
                if (response.length() >= 0 && response.length() != info.sizeBytes) throw new IOException("APK size mismatch");
                byte[] buffer = new byte[32 * 1024];
                long total = 0;
                int count;
                while ((count = response.body().read(buffer)) != -1) {
                    interrupted();
                    total += count;
                    if (total > info.sizeBytes) throw new IOException("Oversized update APK");
                    output.write(buffer, 0, count);
                    digest.update(buffer, 0, count);
                    progress.received(total, info.sizeBytes);
                }
                if (total != info.sizeBytes || !hex(digest.digest()).equals(info.sha256)) throw new IOException("APK integrity mismatch");
                output.getFD().sync();
            }
            interrupted();
            if (destination.exists() && !destination.delete()) throw new IOException("Cannot replace cached APK");
            if (!partial.renameTo(destination)) throw new IOException("Cannot stage update APK");
            published = true;
        } finally {
            if (!published) partial.delete();
        }
    }

    public static boolean matches(File file, UpdateInfo info) throws IOException {
        if (!file.isFile() || file.length() != info.sizeBytes) return false;
        MessageDigest digest = sha256();
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                interrupted();
                digest.update(buffer, 0, count);
            }
        }
        return hex(digest.digest()).equals(info.sha256);
    }

    private static void interrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
    }
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    public static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(Character.forDigit((value & 255) >>> 4, 16)).append(Character.forDigit(value & 15, 16));
        return result.toString();
    }
}
