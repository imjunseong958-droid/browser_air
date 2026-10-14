package dev.air.navigation;

import java.net.URI;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/** Firebase's Android SDK returns encrypted data in intent extras, rather than an OAuth query. */
public final class FirebaseAuthFlow {
    private static final long TIMEOUT_MS = 15 * 60 * 1000L;
    private final String packageName;
    private final String certificateSha1;
    private final String eventId;
    private final String callbackScheme;
    private final LongSupplier clock;
    private final long startedAt;
    private boolean consumed;

    private FirebaseAuthFlow(String packageName, String certificateSha1, String eventId,
                             String callbackScheme, LongSupplier clock) {
        this.packageName = packageName;
        this.certificateSha1 = certificateSha1;
        this.eventId = eventId;
        this.callbackScheme = callbackScheme;
        this.clock = clock;
        startedAt = clock.getAsLong();
    }

    public static FirebaseAuthFlow capture(String url) {
        return capture(url, () -> System.nanoTime() / 1_000_000L);
    }

    public static FirebaseAuthFlow capture(String url, LongSupplier clock) {
        WebAddress source = WebAddress.parse(url);
        if (source == null || !source.scheme().equals("https") || !source.path().equals("/__/auth/handler")) return null;
        Map<String, String> query = source.parameters();
        String type = query.get("authType");
        if (!"signInWithRedirect".equals(type) && !"verifyApp".equals(type)) return null;
        String clientPackage = query.get("apn");
        String fingerprint = query.get("sha1Cert");
        String event = query.get("eventId");
        if (clientPackage == null || clientPackage.isBlank() || event == null
                || fingerprint == null || !fingerprint.matches("(?i)[0-9a-f]{40}")
                || query.getOrDefault("apiKey", "").isBlank()
                || query.getOrDefault("publicKey", "").isBlank()) return null;
        try {
            if (!UUID.fromString(event).toString().equalsIgnoreCase(event)) return null;
        } catch (IllegalArgumentException ignored) { return null; }
        return new FirebaseAuthFlow(clientPackage, fingerprint, event,
                type.equals("verifyApp") ? "recaptcha" : "genericidp", clock);
    }

    public String packageName() { return packageName; }
    public String certificateSha1() { return certificateSha1; }
    public String callbackUri() { return callbackScheme + "://firebase.auth/"; }
    public String activityClass() {
        return "com.google.firebase.auth.internal." + (callbackScheme.equals("recaptcha") ? "RecaptchaActivity" : "GenericIdpActivity");
    }
    public boolean isActive() { return !consumed && clock.getAsLong() - startedAt <= TIMEOUT_MS; }

    /** The caller's installed certificate must be validated by the Android bridge before dispatch. */
    public boolean matches(String uri, String returnedEvent, String encryptedLink, String error) {
        if (!isActive() || !eventId.equals(returnedEvent)) return false;
        if ((encryptedLink == null || encryptedLink.isBlank()) && (error == null || error.isBlank())) return false;
        if ((encryptedLink != null && encryptedLink.length() > 32768) || (error != null && error.length() > 8192)) return false;
        try {
            URI target = URI.create(uri);
            return callbackScheme.equals(target.getScheme()) && "firebase.auth".equals(target.getRawAuthority())
                    && "/".equals(target.getRawPath()) && target.getRawFragment() == null;
        } catch (IllegalArgumentException ignored) { return false; }
    }

    public void consume() { consumed = true; }
}
