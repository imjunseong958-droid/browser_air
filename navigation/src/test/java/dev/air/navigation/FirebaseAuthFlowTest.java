package dev.air.navigation;

import static org.junit.Assert.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

public final class FirebaseAuthFlowTest {
    private static final String EVENT = "12345678-1234-1234-1234-123456789abc";
    private String source(String type) {
        return "https://auth.example/__/auth/handler?authType=" + type
                + "&apn=example.client&sha1Cert=0123456789012345678901234567890123456789"
                + "&eventId=" + EVENT + "&apiKey=test-key&publicKey=test-public-key";
    }

    @Test public void capturesNativeSdkMetadataAndKeepsEncryptedPayloadOpaque() {
        FirebaseAuthFlow flow = FirebaseAuthFlow.capture(source("signInWithRedirect"));
        assertNotNull(flow);
        assertEquals("example.client", flow.packageName());
        assertEquals("com.google.firebase.auth.internal.GenericIdpActivity", flow.activityClass());
        assertTrue(flow.matches("genericidp://firebase.auth/", EVENT, "opaque-encrypted-data", null));
    }

    @Test public void callbackRequiresExactSchemeHostPathAndEvent() {
        FirebaseAuthFlow flow = FirebaseAuthFlow.capture(source("signInWithRedirect"));
        assertFalse(flow.matches("genericidp://firebase.auth/", "wrong", "payload", null));
        assertFalse(flow.matches("genericidp://firebase.auth/other", EVENT, "payload", null));
        assertFalse(flow.matches("genericidp://evil.example/", EVENT, "payload", null));
        assertFalse(flow.matches("recaptcha://firebase.auth/", EVENT, "payload", null));
        assertFalse(flow.matches("genericidp://firebase.auth/", EVENT, null, null));
    }

    @Test public void phoneRecaptchaReturnUsesItsOwnSdkActivity() {
        FirebaseAuthFlow flow = FirebaseAuthFlow.capture(source("verifyApp"));
        assertNotNull(flow);
        assertEquals("com.google.firebase.auth.internal.RecaptchaActivity", flow.activityClass());
        assertTrue(flow.matches("recaptcha://firebase.auth/", EVENT, "encrypted-token-link", null));
        assertFalse(flow.matches("genericidp://firebase.auth/", EVENT, "payload", null));
    }

    @Test public void arbitraryWebsitesCannotCreateABrokerFromIncompleteMetadata() {
        assertNull(FirebaseAuthFlow.capture(source("signInWithRedirect").replace("https:", "http:")));
        assertNull(FirebaseAuthFlow.capture(source("unknown")));
        assertNull(FirebaseAuthFlow.capture(source("signInWithRedirect").replace("/__/auth/handler", "/article")));
        assertNull(FirebaseAuthFlow.capture(source("signInWithRedirect").replace(EVENT, "not-an-event-id")));
        assertNull(FirebaseAuthFlow.capture(source("signInWithRedirect").replace("&publicKey=test-public-key", "")));
        assertNull(FirebaseAuthFlow.capture(source("signInWithRedirect") + "&eventId=" + EVENT));
    }

    @Test public void staleOrConsumedBrokerReturnsAreRejected() {
        AtomicLong now = new AtomicLong(0);
        FirebaseAuthFlow flow = FirebaseAuthFlow.capture(source("signInWithRedirect"), now::get);
        now.set(16 * 60 * 1000L);
        assertFalse(flow.matches(flow.callbackUri(), EVENT, "payload", null));
        flow = FirebaseAuthFlow.capture(source("signInWithRedirect"));
        flow.consume();
        assertFalse(flow.matches(flow.callbackUri(), EVENT, "payload", null));
    }
}
