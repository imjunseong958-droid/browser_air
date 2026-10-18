package dev.air.browser;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.Intent;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.SystemClock;
import android.os.Handler;
import android.os.Looper;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import androidx.lifecycle.Lifecycle;
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
import com.google.android.gms.auth.api.phone.SmsRetriever;
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
        InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getSharedPreferences("sms_assist", Context.MODE_PRIVATE).edit().clear().commit();
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

    @Test public void sameSiteNavigationWorksAndCrossSiteClickIsBlocked() {
        open(server.url("/"));
        text("SAME_SITE_LINK").click();
        text("INSIDE_PAGE");
        awaitUrl("/inside");
        text("OTHER_SITE_LINK").click();
        text("이 작업에서 이어 열까요?");
        assertFalse("The external host received an HTTP request", server.otherHostVisited.get());
        text("돌아가기").click();
        assertTrue("Current address: " + displayedUrl(), displayedUrl().endsWith("/inside"));
    }

    @Test public void anExplicitContinuationOpensTheDisplayedDestination() {
        open(server.url("/"));
        text("SAME_SITE_LINK").click();
        text("OTHER_SITE_LINK").click();
        text("이 작업에서 이어 열까요?");
        assertFalse(server.otherHostVisited.get());
        text("이어서 열기").click();
        text("OTHER_HOST");
        assertTrue(server.otherHostVisited.get());
    }

    @Test public void backgroundingCancelsAnUnconfirmedContinuation() {
        open(server.url("/inside"));
        text("OTHER_SITE_LINK").click();
        text("이 작업에서 이어 열까요?");
        scenario.moveToState(Lifecycle.State.CREATED);
        scenario.onActivity(activity -> assertNull(member(activity, "pendingNavigation")));
        assertFalse(server.otherHostVisited.get());
        scenario.moveToState(Lifecycle.State.RESUMED);
        assertEquals(server.url("/inside"), displayedUrl());
        assertFalse(server.otherHostVisited.get());
    }

    @Test public void aWrappedExternalEntryLoadsOnlyItsCheckedDestination() {
        open("https://www.google.com/url?q=" + Uri.encode(server.url("/inside")));
        text("INSIDE_PAGE");
        assertEquals(server.url("/inside"), displayedUrl());
    }

    @Test public void externalPopupsRequireAChoiceAndUseTheCurrentPage() {
        open(server.url("/inside"));
        text("OTHER_SITE_POPUP").click();
        text("이 작업에서 이어 열까요?");
        assertFalse(server.otherHostVisited.get());
        text("이어서 열기").click();
        text("OTHER_HOST");
        assertTrue(server.otherHostVisited.get());
        scenario.onActivity(activity -> assertEquals(1, ((java.util.List<?>) member(activity, "sessions")).size()));
    }

    @Test public void scriptCannotNavigateOutsideLoadedSite() {
        open(server.url("/"));
        text("SCRIPT_ESCAPE").click();
        device.waitForIdle();
        assertFalse(server.otherHostVisited.get());
        assertEquals(server.url("/"), displayedUrl());
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

    private Object member(Object target, String name) {
        try {
            var field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private void awaitSmsButton() {
        long end = SystemClock.elapsedRealtime() + 20_000;
        while (SystemClock.elapsedRealtime() < end) {
            AtomicBoolean enabled = new AtomicBoolean();
            scenario.onActivity(activity -> enabled.set(activity.findViewById(R.id.smsAssistButton).isEnabled()));
            if (enabled.get()) return;
            SystemClock.sleep(100);
        }
        fail("SMS content bridge did not become ready");
    }

    private Object pendingSms() {
        AtomicReference<Object> request = new AtomicReference<>();
        scenario.onActivity(activity -> request.set(member(member(activity, "smsAssist"), "pending")));
        return request.get();
    }

    private void awaitSmsComplete() {
        // The page's submit event precedes the extension's post-submit value check.
        long end = SystemClock.elapsedRealtime() + 5_000;
        while (SystemClock.elapsedRealtime() < end) {
            if (pendingSms() == null) return;
            SystemClock.sleep(50);
        }
        fail("SMS completion was not acknowledged");
    }

    private Object startSms() {
        awaitSmsButton();
        text("문자 자동 로그인 켜기").click();
        return awaitPendingSms();
    }

    private void markConsent(Object assist, Object request) throws ReflectiveOperationException {
        var phase = request.getClass().getDeclaredField("phase");
        phase.setAccessible(true);
        for (Object value : phase.getType().getEnumConstants()) {
            if (value.toString().equals("CONSENT")) phase.set(request, value);
        }
        var consent = BrowserSmsAssist.class.getDeclaredField("consentRequest");
        consent.setAccessible(true);
        consent.set(assist, request);
        if (member(assist, "pending") == request) {
            var stop = BrowserSmsAssist.class.getDeclaredMethod("stopReceiver");
            stop.setAccessible(true);
            stop.invoke(assist);
        }
    }

    /** Simulate the approved one-message result, then use the real result handler and Gecko bridge. */
    private void receiveSms(Object request, String message) {
        scenario.onActivity(activity -> {
            try {
                Object assist = member(activity, "smsAssist");
                markConsent(assist, request);
                var result = BrowserSmsAssist.class.getDeclaredMethod("consentResult", int.class, Intent.class);
                result.setAccessible(true);
                result.invoke(assist, android.app.Activity.RESULT_OK,
                        new Intent().putExtra(SmsRetriever.EXTRA_SMS_MESSAGE, message));
            } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        });
    }

    @Test public void installedApkRequestsNoSmsPermissions() throws Exception {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String[] permissions = app.getPackageManager().getPackageInfo(app.getPackageName(), PackageManager.GET_PERMISSIONS).requestedPermissions;
        for (String permission : permissions) {
            assertFalse(permission, java.util.Set.of("android.permission.RECEIVE_SMS", "android.permission.READ_SMS",
                    "android.permission.SEND_SMS").contains(permission));
        }
    }

    private Object awaitPendingSms() {
        long end = SystemClock.elapsedRealtime() + 20_000;
        while (SystemClock.elapsedRealtime() < end) {
            Object request = pendingSms();
            if (request != null && member(request, "phase").toString().equals("WAITING")) return request;
            SystemClock.sleep(100);
        }
        throw new AssertionError("SMS did not automatically start waiting");
    }

    @Test public void smsWaitsOnlyOnRecognizedVerificationScreens() {
        open(server.url("/"));
        text("FIXTURE_HOME");
        awaitSmsButton();
        text("문자 자동 로그인 켜기").click();
        assertNull(pendingSms());
        scenario.onActivity(activity -> ((GeckoView) activity.findViewById(R.id.geckoView)).getSession()
                .loadUri(server.url("/sms")));
        text("SMS_FIXTURE");
        receiveSms(awaitPendingSms(), "인증번호 001234");
        text("OTP_SUBMITTED_001234");
    }

    @Test public void smsApprovedMessageFillsAndSubmitsAfterInitialOptIn() {
        open(server.url("/sms"));
        text("SMS_FIXTURE");
        awaitSmsButton();
        assertNull(pendingSms());
        receiveSms(startSms(), "[AIR] 인증번호 001234");
        text("OTP_SUBMITTED_001234");
        awaitSmsComplete();
    }

    @Test public void smsLabelledFieldIsChosenAutomaticallyWithoutTouchingPhone() {
        open(server.url("/sms-choice"));
        text("SMS_FIXTURE");
        receiveSms(startSms(), "인증번호 123456");
        text("OTP_SUBMITTED_123456");
        assertFalse(device.hasObject(By.text("PHONE_CHANGED")));
        assertFalse(device.hasObject(By.text("인증번호 입력칸을 선택해 주세요")));
    }

    @Test public void smsSingleUnlabelledCodeDoesNotNeedCodeSelection() {
        open(server.url("/sms"));
        text("SMS_FIXTURE");
        receiveSms(startSms(), "[AIR] 001234");
        text("OTP_SUBMITTED_001234");
    }

    @Test public void smsAmbiguousCodesStillRequireUserChoice() {
        open(server.url("/sms"));
        text("SMS_FIXTURE");
        receiveSms(startSms(), "인증번호 123456 / 확인코드 654321");
        text("입력할 인증번호를 선택해 주세요");
        text("654321").click();
        text("OTP_SUBMITTED_654321");
    }

    @Test public void smsNavigationStartsNewWaitAndRejectsPreviousMessage() {
        open(server.url("/sms"));
        text("SMS_FIXTURE");
        Object old = startSms();
        scenario.onActivity(activity -> ((GeckoView) activity.findViewById(R.id.geckoView)).getSession()
                .loadUri(server.url("/sms?second")));
        awaitUrl("/sms?second");
        Object next = awaitPendingSms();
        assertNotSame(old, next);
        receiveSms(old, "인증번호 123456");
        assertSame(next, pendingSms());
        text("OTP_WAITING");
        receiveSms(next, "인증번호 654321");
        text("OTP_SUBMITTED_654321");
    }

    @Test public void smsDisablePersistsAndDoesNotAutoRestart() {
        open(server.url("/sms"));
        text("SMS_FIXTURE");
        Object old = startSms();
        text("인증 문자 기다리는 중 · 문자 허용 필요 · 끄기").click();
        assertNull(pendingSms());
        receiveSms(old, "인증번호 123456");
        text("OTP_WAITING");
        scenario.close();
        open(server.url("/sms"));
        text("SMS_FIXTURE");
        awaitSmsButton();
        assertNull(pendingSms());
    }

    @Test public void smsBackgroundingDiscardsOldMessageAndResumeAutomaticallyRearms() {
        open(server.url("/sms"));
        text("SMS_FIXTURE");
        Object old = startSms();
        scenario.moveToState(Lifecycle.State.CREATED);
        assertNull(pendingSms());
        scenario.moveToState(Lifecycle.State.RESUMED);
        Object next = awaitPendingSms();
        assertNotSame(old, next);
        receiveSms(old, "인증번호 123456");
        text("OTP_WAITING");
        receiveSms(next, "인증번호 654321");
        text("OTP_SUBMITTED_654321");
    }

    @Test public void smsCannotFillBeforeOneMessageConsent() {
        open(server.url("/sms"));
        text("SMS_FIXTURE");
        Object request = startSms();
        scenario.onActivity(activity -> {
            try {
                var accept = BrowserSmsAssist.class.getDeclaredMethod("acceptMessage", request.getClass(), String.class);
                accept.setAccessible(true);
                accept.invoke(member(activity, "smsAssist"), request, "인증번호 123456");
            } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        });
        assertSame(request, pendingSms());
        text("OTP_WAITING");
        receiveSms(request, "인증번호 654321");
        text("OTP_SUBMITTED_654321");
    }

    @Test public void smsSplitFieldsFillAndSubmitAutomatically() {
        open(server.url("/sms-split"));
        text("SMS_FIXTURE");
        receiveSms(startSms(), "인증번호 001234");
        text("OTP_SUBMITTED_001234");
        awaitSmsComplete();
    }

    @Test public void smsApprovedMessageWaitsForLateEnabledFieldAndButton() {
        open(server.url("/sms-delayed"));
        text("SMS_FIXTURE");
        Object request = startSms();
        text("인증번호 발송").click();
        receiveSms(request, "인증번호 001234");
        text("OTP_SUBMITTED_001234");
        awaitSmsComplete();
    }

    @Test public void smsReplacedInputIsRetriedWithinTheApprovedForm() {
        open(server.url("/sms-replaced"));
        text("SMS_FIXTURE");
        receiveSms(startSms(), "인증번호 001234");
        text("OTP_SUBMITTED_001234");
        awaitSmsComplete();
    }

    @Test public void smsRejectedInputShowsFallbackInsteadOfClaimingSuccess() {
        open(server.url("/sms-rejected"));
        text("SMS_FIXTURE");
        receiveSms(startSms(), "인증번호 001234");
        text("인증번호를 직접 입력해 주세요");
        assertNull(pendingSms());
        assertFalse(device.hasObject(By.text("OTP_SUBMITTED_001234")));
        text("확인").click();
        text("문자 자동 로그인 켜짐 · 끄기");
    }

    @Test public void smsUnlabelledCodeBesideNameIsFilledWithoutChoosingAfterRequestButtonDisappears() {
        open(server.url("/sms-unlabelled"));
        text("SMS_FIXTURE");
        Object request = startSms();
        text("인증번호 발송").click();
        SystemClock.sleep(300);
        assertSame(request, pendingSms());
        receiveSms(request, "인증번호 001234");
        text("OTP_SUBMITTED_001234");
        assertFalse(device.hasObject(By.text("NAME_CHANGED")));
        assertFalse(device.hasObject(By.text("인증번호 입력칸을 선택해 주세요")));
        awaitSmsComplete();
    }

    @Test public void smsRejectedSyntheticInputRecoversThroughAndroidKeyboardWithoutChoosing() {
        open(server.url("/sms-native"));
        text("SMS_FIXTURE");
        receiveSms(startSms(), "인증번호 001234");
        text("OTP_SUBMITTED_001234");
        text("NATIVE_INPUT_ACCEPTED");
        assertFalse(device.hasObject(By.text("인증번호 입력칸을 선택해 주세요")));
        awaitSmsComplete();
    }

    @Test public void smsIgnoredSyntheticInputRecoversWhenValueStaysButButtonIsDisabled() {
        open(server.url("/sms-native-retained"));
        text("SMS_FIXTURE");
        Object request = startSms();
        scenario.onActivity(activity -> {
            InputConnection connection = ((GeckoView) activity.findViewById(R.id.geckoView)).getSession()
                    .getTextInput().onCreateInputConnection(new EditorInfo());
            assertNotNull(connection);
            Handler editor = connection.getHandler();
            assertNotNull(editor);
            assertNotEquals(Looper.getMainLooper(), editor.getLooper());
        });
        receiveSms(request, "인증번호 001234");
        text("OTP_SUBMITTED_001234");
        text("NATIVE_INPUT_ACCEPTED");
        awaitSmsComplete();
    }

    @Test public void smsVerificationClickThatRejectsCodeShowsFallback() {
        open(server.url("/sms-click-rejected"));
        text("SMS_FIXTURE");
        receiveSms(startSms(), "인증번호 001234");
        text("인증번호를 직접 입력해 주세요");
        assertNull(pendingSms());
        text("확인").click();
        text("OTP_REJECTED");
    }

    @Test public void smsPhoneRequestTransitionsToWrappedOtpAndSubmitsAcceptedInput() {
        open(server.url("/sms-phone-step"));
        text("SMS_PHONE_STEP");
        Object request = startSms();
        text("인증번호 발송").click();
        text("SMS_CODE_STEP");
        assertSame(request, pendingSms());
        receiveSms(request, "[쿠팡] 인증번호 001234");
        text("OTP_SUBMITTED_001234");
        text("NATIVE_INPUT_ACCEPTED");
        assertFalse(device.hasObject(By.text("PHONE_CHANGED")));
        assertFalse(device.hasObject(By.text("인증번호 입력칸을 선택해 주세요")));
        awaitSmsComplete();
    }

    @Test public void smsNativeInputIsUsedEvenWhenLoginButtonAlreadyEnabled() {
        open(server.url("/sms-native-enabled"));
        text("SMS_FIXTURE");
        receiveSms(startSms(), "인증번호 001234");
        text("OTP_SUBMITTED_001234");
        text("NATIVE_INPUT_ACCEPTED");
        assertFalse(device.hasObject(By.text("SITE_DID_NOT_ACCEPT_INPUT")));
        awaitSmsComplete();
    }

    @Test public void smsWithoutLoginButtonReportsIncompleteInsteadOfClaimingInput() {
        open(server.url("/sms-no-button"));
        text("SMS_FIXTURE");
        receiveSms(startSms(), "인증번호 001234");
        text("자동 로그인을 완료하지 못했어요");
        assertFalse(device.hasObject(By.text("인증번호를 입력했어요. 사이트의 확인 또는 로그인 버튼을 눌러 주세요.")));
        assertFalse(device.hasObject(By.text("OTP_SUBMITTED_001234")));
        awaitSmsComplete();
        text("확인").click();
    }

    @Test public void smsMalformedBroadcastDoesNotInterruptWaiting() {
        open(server.url("/sms"));
        text("SMS_FIXTURE");
        Object request = startSms();
        scenario.onActivity(activity -> {
            BroadcastReceiverForTest.receive(member(activity, "smsAssist"), activity,
                    new Intent(SmsRetriever.SMS_RETRIEVED_ACTION));
            BroadcastReceiverForTest.receive(member(activity, "smsAssist"), activity,
                    new Intent("android.provider.Telephony.SMS_RECEIVED"));
        });
        assertSame(request, pendingSms());
        assertEquals("WAITING", member(request, "phase").toString());
    }

    @Test public void smsEnabledSettingAutomaticallyAppliesAfterReopeningBrowser() {
        open(server.url("/sms"));
        text("SMS_FIXTURE");
        startSms();
        scenario.close();
        open(server.url("/sms"));
        text("SMS_FIXTURE");
        receiveSms(awaitPendingSms(), "인증번호 123456");
        text("OTP_SUBMITTED_123456");
    }

    @Test public void smsConsentResultWhilePausedAutomaticallyCompletesOnResume() {
        open(server.url("/sms"));
        text("SMS_FIXTURE");
        Object request = startSms();
        scenario.onActivity(activity -> {
            try {
                markConsent(member(activity, "smsAssist"), request);
            } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        });
        scenario.moveToState(Lifecycle.State.CREATED);
        assertSame(request, pendingSms());
        receiveSms(request, "인증번호 001234");
        assertEquals("CODES", member(request, "phase").toString());
        scenario.moveToState(Lifecycle.State.RESUMED);
        text("OTP_SUBMITTED_001234");
        awaitSmsComplete();
    }

    @Test public void smsMismatchedConsentMessageEndsTheOneMessageRequest() {
        open(server.url("/sms"));
        text("SMS_FIXTURE");
        Object request = startSms();
        receiveSms(request, "인증번호 123456\n@other.example #123456");
        assertNull(pendingSms());
        text("OTP_WAITING");
    }

    @Test public void smsDeniedConsentDoesNotFillOrImmediatelyRequestAgain() {
        open(server.url("/sms"));
        text("SMS_FIXTURE");
        Object request = startSms();
        scenario.onActivity(activity -> {
            try {
                Object assist = member(activity, "smsAssist");
                markConsent(assist, request);
                var result = BrowserSmsAssist.class.getDeclaredMethod("consentResult", int.class, Intent.class);
                result.setAccessible(true);
                result.invoke(assist, android.app.Activity.RESULT_CANCELED, null);
            } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        });
        assertNull(pendingSms());
        text("OTP_WAITING");
        text("문자 자동 로그인 켜짐 · 끄기");
    }

    private static class BroadcastReceiverForTest {
        static void receive(Object assist, Context context, Intent intent) {
            try {
                var field = BrowserSmsAssist.class.getDeclaredField("receiver");
                field.setAccessible(true);
                ((android.content.BroadcastReceiver) field.get(assist)).onReceive(context, intent);
            } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        }
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
                    if (route.equals("/sms-phone-step")) {
                        // The phone input/label shape was observed on Coupang's public entry page.
                        // The post-SMS widget models a controlled formless login; no service account is used.
                        content = "<h1>SMS_PHONE_STEP</h1><section id='steps'><label>🇰🇷+82<input type='tel' placeholder='휴대폰번호' value='01000000000'></label>"
                                + "<button id='send'>인증번호 발송</button></section><p id='status'></p><p id='native-status'></p><p id='phone-status'></p>"
                                + "<script>document.querySelector('input').addEventListener('input',()=>document.getElementById('phone-status').textContent='PHONE_CHANGED');"
                                + "document.getElementById('send').onclick=function(){this.remove();document.querySelector('h1').textContent='SMS_CODE_STEP';"
                                + "document.getElementById('steps').insertAdjacentHTML('beforeend',\"<div><div><div><label>03:00<input id='otp' type='tel' placeholder='인증번호 6자리' maxlength='6'></label></div></div></div><button id='login'>로그인</button>\");"
                                + "let accepted='';document.getElementById('otp').addEventListener('input',e=>{if(e.isTrusted){accepted=e.target.value;document.getElementById('native-status').textContent='NATIVE_INPUT_ACCEPTED'}});"
                                + "document.getElementById('login').onclick=()=>document.getElementById('status').textContent=accepted?'OTP_SUBMITTED_'+accepted:'SITE_DID_NOT_ACCEPT_INPUT'};</script>";
                    }
                    else if (route.equals("/sms-no-button")) {
                        content = "<h1>SMS_FIXTURE</h1><section><input placeholder='인증번호' maxlength='6'></section>";
                    }
                    else if (route.startsWith("/sms")) {
                        String fields = route.equals("/sms-split") ? "<div aria-label='인증번호'><input maxlength='1' autocomplete='one-time-code'><input maxlength='1'><input maxlength='1'><input maxlength='1'><input maxlength='1'><input maxlength='1'></div>"
                                : route.equals("/sms-unlabelled") ? "<input id='verificationName' placeholder='이름'><input id='entry' placeholder='--' maxlength='6'><button type='button' id='send' onclick='this.remove()'>인증번호 발송</button>"
                                : (route.equals("/sms-choice") ? "<label for='phone'>전화번호</label><input type='tel' id='phone'>" : "")
                                    + "<label for='otp'>인증번호</label><input id='otp' "
                                    + (route.equals("/sms-choice") ? "inputmode='numeric'" : "autocomplete='one-time-code'") + ">";
                        String setup = route.equals("/sms-delayed")
                                ? "document.getElementById('otp').disabled=true;document.querySelector('#verify button').disabled=true;"
                                    + "document.getElementById('verify').insertAdjacentHTML('beforeend','<button type=\"button\" id=\"send\">인증번호 발송</button>');"
                                    + "document.getElementById('send').addEventListener('click',()=>setTimeout(()=>document.getElementById('otp').disabled=false,3000));"
                                    + "document.getElementById('otp').addEventListener('input',()=>setTimeout(()=>document.querySelector('#verify button').disabled=false,2500));"
                                : route.equals("/sms-replaced")
                                    ? "document.getElementById('otp').addEventListener('input',()=>setTimeout(()=>{document.getElementById('otp').outerHTML=\"<input id='otp' autocomplete='one-time-code'>\"},20),{once:true});"
                                : route.equals("/sms-rejected")
                                    ? "document.getElementById('otp').addEventListener('input',e=>setTimeout(()=>e.target.value='',20));"
                                : route.equals("/sms-native")
                                    ? "document.body.insertAdjacentHTML('beforeend','<p id=\"native-status\"></p>');"
                                        + "document.getElementById('otp').addEventListener('input',e=>{if(!e.isTrusted)e.target.value='';else document.getElementById('native-status').textContent='NATIVE_INPUT_ACCEPTED'});"
                                : route.equals("/sms-native-retained")
                                    ? "document.getElementById('otp').focus();document.body.insertAdjacentHTML('beforeend','<p id=\"native-status\"></p>');document.querySelector('#verify button').disabled=true;"
                                        + "document.getElementById('otp').addEventListener('input',e=>{if(e.isTrusted){document.querySelector('#verify button').disabled=false;document.getElementById('native-status').textContent='NATIVE_INPUT_ACCEPTED'}});"
                                : route.equals("/sms-native-enabled")
                                    ? "let accepted='';document.body.insertAdjacentHTML('beforeend','<p id=\"native-status\"></p>');"
                                        + "document.getElementById('otp').addEventListener('input',e=>{if(e.isTrusted){accepted=e.target.value;document.getElementById('native-status').textContent='NATIVE_INPUT_ACCEPTED'}});"
                                        + "document.getElementById('verify').addEventListener('submit',e=>{if(!accepted){e.preventDefault();e.stopImmediatePropagation();document.getElementById('status').textContent='SITE_DID_NOT_ACCEPT_INPUT'}},true);"
                                : route.equals("/sms-click-rejected")
                                    ? "document.querySelector('#verify button').addEventListener('click',()=>setTimeout(()=>{document.getElementById('otp').value='';document.getElementById('status').textContent='OTP_REJECTED'},20));"
                                : route.equals("/sms-unlabelled")
                                    ? "document.getElementById('verificationName').addEventListener('input',()=>document.getElementById('phone-status').textContent='NAME_CHANGED');" : "";
                        content = "<h1>SMS_FIXTURE</h1><form id='verify'>" + fields + "<button>VERIFY_OTP</button></form>"
                                + "<p id='status'>OTP_WAITING</p><p id='phone-status'></p><script>"
                                + "document.getElementById('otp')?.addEventListener('input',e=>document.getElementById('status').textContent='OTP_VALUE_'+e.target.value);"
                                + "document.getElementById('phone')?.addEventListener('input',()=>document.getElementById('phone-status').textContent='PHONE_CHANGED');"
                                + "document.getElementById('verify').addEventListener('submit',e=>{e.preventDefault();document.getElementById('status').textContent='OTP_SUBMITTED_'+[...document.querySelectorAll('#verify input:not(#phone):not(#verificationName)')].map(i=>i.value).join('')});"
                                + setup + "</script>";
                    }
                    else if (route.equals("/inside")) content = "<h1>INSIDE_PAGE</h1><a href='" + other + "'>OTHER_SITE_LINK</a>"
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
