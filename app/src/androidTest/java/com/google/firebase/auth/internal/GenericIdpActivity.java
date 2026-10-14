package com.google.firebase.auth.internal;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

/** A test-only receiver with the SDK's public manifest contract; no Firebase SDK is included in AIR. */
public final class GenericIdpActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        boolean fields = getIntent().hasExtra("eventId") && "encrypted-test-payload".equals(getIntent().getStringExtra("link"))
                && getIntent().getBooleanExtra("encryptionEnabled", false);
        TextView label = new TextView(this);
        label.setText(fields ? "FIREBASE_CALLBACK_RECEIVED" : "FIREBASE_FIELDS_MISSING");
        label.setTextSize(24);
        setContentView(label);
    }
}
