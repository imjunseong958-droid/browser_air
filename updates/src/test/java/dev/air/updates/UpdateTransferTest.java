package dev.air.updates;

import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class UpdateTransferTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final byte[] bytes = "A small APK transport fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private UpdateInfo info(byte[] content) throws Exception {
        return new UpdateInfo(1009, "0.1.2+1009", 29, UpdateInfo.apkUrl(1009),
                UpdateTransfer.hex(MessageDigest.getInstance("SHA-256").digest(content)), content.length);
    }
    private UpdateTransport transport(byte[] content, long length, AtomicBoolean closed) {
        return url -> new UpdateTransport.Response() {
            final InputStream input = new ByteArrayInputStream(content);
            public InputStream body() { return input; }
            public long length() { return length; }
            public void close() throws IOException { closed.set(true); input.close(); }
        };
    }
    private void noPartialFiles(File directory) {
        assertEquals(0, directory.listFiles((dir, name) -> name.endsWith(".part")).length);
    }
    @Test public void completeDownloadMatchesManifestAndReportsProgress() throws Exception {
        File apk = new File(temporary.newFolder(), "AIR.apk");
        AtomicBoolean closed = new AtomicBoolean();
        AtomicLong received = new AtomicLong();
        UpdateInfo info = info(bytes);
        UpdateTransfer.download(transport(bytes, bytes.length, closed), info, apk, (count, total) -> received.set(count));
        assertArrayEquals(bytes, Files.readAllBytes(apk.toPath()));
        assertTrue(UpdateTransfer.matches(apk, info)); assertTrue(closed.get());
        assertEquals(bytes.length, received.get()); noPartialFiles(apk.getParentFile());
    }
    @Test public void badChecksumCannotReplaceAnExistingCachedFile() throws Exception {
        File apk = temporary.newFile("AIR.apk"); Files.writeString(apk.toPath(), "previous APK");
        byte[] corrupt = bytes.clone(); corrupt[0] ^= 1;
        assertThrows(IOException.class, () -> UpdateTransfer.download(transport(corrupt, -1, new AtomicBoolean()),
                info(bytes), apk, (a, b) -> {}));
        assertEquals("previous APK", Files.readString(apk.toPath())); noPartialFiles(apk.getParentFile());
    }
    @Test public void truncatedAndOversizedStreamsDoNotStageApks() throws Exception {
        for (byte[] content : new byte[][]{java.util.Arrays.copyOf(bytes, bytes.length - 1), java.util.Arrays.copyOf(bytes, bytes.length + 1)}) {
            File apk = new File(temporary.newFolder(), "AIR.apk");
            assertThrows(IOException.class, () -> UpdateTransfer.download(transport(content, -1, new AtomicBoolean()), info(bytes), apk, (a, b) -> {}));
            assertFalse(apk.exists()); noPartialFiles(apk.getParentFile());
        }
    }
    @Test public void incorrectContentLengthFailsBeforeStaging() throws Exception {
        File apk = new File(temporary.newFolder(), "AIR.apk"); AtomicBoolean closed = new AtomicBoolean();
        assertThrows(IOException.class, () -> UpdateTransfer.download(transport(bytes, bytes.length + 1, closed), info(bytes), apk, (a, b) -> {}));
        assertFalse(apk.exists()); assertTrue(closed.get()); noPartialFiles(apk.getParentFile());
    }
    @Test public void cancellationRemovesPartialData() throws Exception {
        File apk = new File(temporary.newFolder(), "AIR.apk");
        try {
            assertThrows(InterruptedIOException.class, () -> UpdateTransfer.download(transport(bytes, -1, new AtomicBoolean()),
                    info(bytes), apk, (a, b) -> Thread.currentThread().interrupt()));
            assertFalse(apk.exists()); noPartialFiles(apk.getParentFile());
        } finally { Thread.interrupted(); }
    }
    @Test public void cachedApkTamperingIsDetected() throws Exception {
        File apk = temporary.newFile("AIR.apk"); Files.write(apk.toPath(), bytes); UpdateInfo info = info(bytes);
        assertTrue(UpdateTransfer.matches(apk, info));
        byte[] changed = bytes.clone(); changed[0] ^= 1; Files.write(apk.toPath(), changed);
        assertFalse(UpdateTransfer.matches(apk, info));
    }
    @Test public void manifestsAreBoundedWithoutContentLength() {
        AtomicBoolean closed = new AtomicBoolean();
        assertThrows(IOException.class, () -> UpdateTransfer.readManifest(transport(new byte[64 * 1024 + 1], -1, closed)));
        assertTrue(closed.get());
    }
}
