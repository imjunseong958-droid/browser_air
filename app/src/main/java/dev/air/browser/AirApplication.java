package dev.air.browser;

import android.app.Application;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;

public final class AirApplication extends Application {
    private GeckoRuntime runtime;

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
        }
        return runtime;
    }
}
