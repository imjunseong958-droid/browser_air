package dev.air.browser;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

/** Test-only external application receiving an OAuth return. Never packaged in AIR. */
public final class AuthReceiverActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        TextView label = new TextView(this);
        label.setText("AUTH_CALLBACK_RECEIVED");
        label.setTextSize(24);
        setContentView(label);
    }
}
