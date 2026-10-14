package dev.air.browser;

import android.app.Application;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.WebExtension;

public final class AirApplication extends Application {
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
                    .build();
            runtime = GeckoRuntime.create(this, settings);
            smsExtension = runtime.getWebExtensionController().ensureBuiltIn(
                    "resource://android/assets/sms-assist/", BrowserSmsAssist.EXTENSION_ID);
        }
        return runtime;
    }

    GeckoResult<WebExtension> smsExtension() { runtime(); return smsExtension; }
}
