package dev.air.browser;

import android.app.Application;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.WebExtension;

public final class AirApplication extends Application {
    /** Cloudflare's family resolver refuses adult and malware domains; there is no unfiltered fallback. */
    private static final String FILTERED_DNS = "https://family.cloudflare-dns.com/dns-query";
    private GeckoRuntime runtime;
    private GeckoResult<WebExtension> smsExtension;

    /** The home screen and QR camera do not start the browser engine. */
    public synchronized GeckoRuntime runtime() {
        if (runtime == null) {
            GeckoRuntimeSettings settings = new GeckoRuntimeSettings.Builder()
                    .javaScriptEnabled(true)
                    .remoteDebuggingEnabled(false)
                    .consoleOutput(false)
                    .aboutConfigEnabled(false)
                    .loginAutofillEnabled(false)
                    .trustedRecursiveResolverMode(GeckoRuntimeSettings.TRR_MODE_ONLY)
                    .trustedRecursiveResolverUri(FILTERED_DNS)
                    .build();
            runtime = GeckoRuntime.create(this, settings);
            smsExtension = runtime.getWebExtensionController().ensureBuiltIn(
                    "resource://android/assets/sms-assist/", BrowserSmsAssist.EXTENSION_ID);
        }
        return runtime;
    }

    GeckoResult<WebExtension> smsExtension() { runtime(); return smsExtension; }
}
