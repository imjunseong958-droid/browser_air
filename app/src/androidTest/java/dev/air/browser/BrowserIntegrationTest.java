package dev.air.browser;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.Intent;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.SystemClock;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;
import androidx.browser.customtabs.CustomTabsClient;
import androidx.browser.customtabs.CustomTabsSession;
import androidx.browser.customtabs.CustomTabsServiceConnection;
import androidx.browser.customtabs.CustomTabsCallback;
import androidx.browser.customtabs.CustomTabsIntent;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.security.MessageDigest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mozilla.geckoview.GeckoView;

@RunWith(AndroidJUnit4.class)
public final class BrowserIntegrationTest {
    private FixtureServer server;
    private UiDevice device;
    private ActivityScenario<BrowserActivity> scenario;

    @Before public void setup() throws Exception {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        server = new FixtureServer();
    }
    @After public void cleanup() throws Exception {
        if (scenario != null) scenario.close();
        server.close();
    }

    private void open(String url) {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        scenario = ActivityScenario.launch(new Intent(app, BrowserActivity.class)
                .setAction(Intent.ACTION_VIEW).setData(Uri.parse(url)));
    }
    private UiObject2 text(String value) {
        UiObject2 object = device.wait(Until.findObject(By.text(value)), 20_000);
        assertNotNull("Expected visible text: " + value, object);
        return object;
    }
    private String displayedUrl() {
        AtomicReference<String> value = new AtomicReference<>();
        scenario.onActivity(activity -> {
            try {
                var field = BrowserActivity.class.getDeclaredField("currentUrl");
                field.setAccessible(true);
                value.set((String) field.get(activity));
            } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        });
        return value.get();
    }
    private void awaitUrl(String suffix) {
        long end = SystemClock.elapsedRealtime() + 15_000;
        while (SystemClock.elapsedRealtime() < end) {
            String url = displayedUrl();
            if (url != null && url.endsWith(suffix)) return;
            SystemClock.sleep(100);
        }
        fail("Did not navigate to " + suffix + "; current: " + displayedUrl());
    }

    @Test public void searchEntryNeverStartsEngine() {
        open("https://www.google.com/search?q=test");
        text("새로운 탐색은 여기서 멈춰요");
        scenario.onActivity(activity -> assertNull(((GeckoView) activity.findViewById(R.id.geckoView)).getSession()));
    }

    @Test public void sameSiteAndCrossSiteLinksOpenWithoutAPrompt() {
        open(server.url("/"));
        text("SAME_SITE_LINK").click();
        text("INSIDE_PAGE");
        awaitUrl("/inside");
        text("OTHER_SITE_LINK").click();
        text("OTHER_HOST");
        assertTrue(server.otherHostVisited.get());
    }

    @Test public void aWrappedExternalEntryLoadsOnlyItsCheckedDestination() {
        open("https://www.google.com/url?q=" + Uri.encode(server.url("/inside")));
        text("INSIDE_PAGE");
        assertEquals(server.url("/inside"), displayedUrl());
    }

    @Test public void externalPopupsOpenWithoutAPrompt() {
        open(server.url("/inside"));
        text("OTHER_SITE_POPUP").click();
        text("OTHER_HOST");
        assertTrue(server.otherHostVisited.get());
    }

    @Test public void scriptCanNavigateToAnotherSite() {
        open(server.url("/"));
        text("SCRIPT_ESCAPE").click();
        text("OTHER_HOST");
        assertTrue(server.otherHostVisited.get());
    }

    @Test public void popupUsesSamePolicyAndReturnsToParent() {
        open(server.url("/"));
        text("LOGIN_POPUP").click();
        text("POPUP_PAGE");
        awaitUrl("/popup");
        text("CLOSE_POPUP").click();
        text("SAME_SITE_LINK");
        awaitUrl("/");
    }

    @Test public void scopedOAuthCallbackReturnsToInstalledApp() {
        open("https://identity.air-test.invalid/oauth2/authorize?client_id=test&response_type=code"
                + "&redirect_uri=air-test-callback%3A%2F%2Foauth%2Freturn&state=original");
        scenario.onActivity(activity -> ((GeckoView) activity.findViewById(R.id.geckoView)).getSession()
                .loadUri("air-test-callback://oauth/return?code=test-code&state=original"));
        text("AUTH_CALLBACK_RECEIVED");
    }

    @Test public void customTabsClientsCanConnectAndReceiveNavigationCallbacks() throws Exception {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        CountDownLatch connected = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<CustomTabsClient> client = new AtomicReference<>();
        CustomTabsServiceConnection connection = new CustomTabsServiceConnection() {
            @Override public void onCustomTabsServiceConnected(ComponentName name, CustomTabsClient service) {
                client.set(service); connected.countDown();
            }
            @Override public void onServiceDisconnected(ComponentName name) {}
        };
        assertTrue(CustomTabsClient.bindCustomTabsService(app, app.getPackageName(), connection));
        try {
            assertTrue(connected.await(10, TimeUnit.SECONDS));
            assertTrue(client.get().warmup(0));
            AtomicReference<CustomTabsSession> token = new AtomicReference<>();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> token.set(client.get().newSession(new CustomTabsCallback() {
                @Override public void onNavigationEvent(int event, android.os.Bundle extras) {
                    if (event == NAVIGATION_FINISHED) finished.countDown();
                }
            })));
            assertNotNull(token.get());
            Intent intent = new CustomTabsIntent.Builder(token.get()).build().intent;
            intent.setPackage(app.getPackageName()).setData(Uri.parse(server.url("/")));
            scenario = ActivityScenario.launch(intent);
            text("SAME_SITE_LINK");
            assertTrue("Custom Tabs completion callback", finished.await(10, TimeUnit.SECONDS));
        } finally { app.unbindService(connection); }
    }

    @Test public void firebaseSdkIntentExtrasReturnToThePinnedSdkActivity() throws Exception {
        Context testApp = InstrumentationRegistry.getInstrumentation().getContext();
        var client = testApp.getPackageManager().getPackageInfo(testApp.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
        byte[] digest = MessageDigest.getInstance("SHA-1").digest(client.signingInfo.getApkContentsSigners()[0].toByteArray());
        StringBuilder fingerprint = new StringBuilder();
        for (byte value : digest) fingerprint.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        String event = "12345678-1234-1234-1234-123456789abc";
        open("https://auth.air-test.invalid/__/auth/handler?authType=signInWithRedirect&apiKey=test&publicKey=test"
                + "&eventId=" + event + "&apn=" + testApp.getPackageName() + "&sha1Cert=" + fingerprint);
        String wrong = new Intent(Intent.ACTION_VIEW, Uri.parse("genericidp://firebase.auth/"))
                .setPackage(testApp.getPackageName()).putExtra("eventId", "wrong-event")
                .putExtra("link", "encrypted-test-payload").toUri(Intent.URI_INTENT_SCHEME);
        scenario.onActivity(activity -> ((GeckoView) activity.findViewById(R.id.geckoView)).getSession().loadUri(wrong));
        device.waitForIdle();
        assertFalse(device.hasObject(By.text("FIREBASE_CALLBACK_RECEIVED")));
        String returned = new Intent(Intent.ACTION_VIEW, Uri.parse("genericidp://firebase.auth/"))
                .setPackage(testApp.getPackageName()).putExtra("eventId", event)
                .putExtra("link", "encrypted-test-payload").toUri(Intent.URI_INTENT_SCHEME);
        scenario.onActivity(activity -> ((GeckoView) activity.findViewById(R.id.geckoView)).getSession().loadUri(returned));
        text("FIREBASE_CALLBACK_RECEIVED");
    }

    /** Real HTTP fixture on the emulator; changing localhost to 127.0.0.1 is a cross-site escape. */
    private static final class FixtureServer implements AutoCloseable {
        private final ServerSocket socket;
        private final Thread thread;
        private volatile boolean running = true;
        final AtomicBoolean otherHostVisited = new AtomicBoolean();

        FixtureServer() throws Exception {
            socket = new ServerSocket(0);
            thread = new Thread(this::serve, "AIR-test-http");
            thread.setDaemon(true);
            thread.start();
        }
        String url(String path) { return "http://localhost:" + socket.getLocalPort() + path; }
        private void serve() {
            while (running) {
                try (Socket connection = socket.accept()) {
                    connection.setSoTimeout(3000);
                    BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
                    String request = reader.readLine();
                    if (request == null) continue;
                    String route = request.split(" ")[1];
                    String header;
                    while ((header = reader.readLine()) != null && !header.isEmpty()) {
                        if (header.toLowerCase().startsWith("host: 127.0.0.1")) otherHostVisited.set(true);
                    }
                    String other = "http://127.0.0.1:" + socket.getLocalPort() + "/outside";
                    String content;
                    if (route.equals("/inside")) content = "<h1>INSIDE_PAGE</h1><a href='" + other + "'>OTHER_SITE_LINK</a>"
                            + "<a target='_blank' href='" + other + "'>OTHER_SITE_POPUP</a>";
                    else if (route.equals("/outside")) content = "<h1>OTHER_HOST</h1>";
                    else if (route.equals("/popup")) content = "<h1>POPUP_PAGE</h1><button onclick='window.close()'>CLOSE_POPUP</button>";
                    else content = "<h1>FIXTURE_HOME</h1><a href='/inside'>SAME_SITE_LINK</a><br>"
                            + "<button onclick=\"location.href='" + other + "'\">SCRIPT_ESCAPE</button><br>"
                            + "<button onclick=\"window.open('/popup','login')\">LOGIN_POPUP</button>";
                    byte[] body = ("<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>"
                            + "<style>body{font:18px sans-serif;padding:24px}a,button{display:block;margin:24px 0;padding:16px}</style>"
                            + "</head><body>" + content + "</body></html>").getBytes(StandardCharsets.UTF_8);
                    byte[] headers = ("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: "
                            + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8);
                    connection.getOutputStream().write(headers);
                    connection.getOutputStream().write(body);
                } catch (Exception ignored) { if (!running) return; }
            }
        }
        @Override public void close() throws Exception { running = false; socket.close(); thread.join(1500); }
    }
}
