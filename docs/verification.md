# Verification record

Initial browser verification was performed on **14 October 2026 (Asia/Seoul)**. SMS automatic sign-in was completed and checked on **15 October 2026 (Asia/Seoul)** using an ARM64 Pixel 7 emulator running Android 15 (API 35). The app was built for Android 10 and later. No real user accounts or personal login credentials were used.

## Build and installation

- Gradle build and Android Lint completed with zero errors. Some dependency-update and layout recommendations remain as warnings.
- The current ARM64 debug APK passed signature verification and was installed in the emulator. The unsigned, optimized release APK also built successfully; this check does not publish an update. Earlier x86_64 test APKs are no longer distributed.
- `dev.air.browser` was successfully registered for Android's default browser role.
- The independent browser engine and authentication callbacks were checked with Chrome disabled in the emulator.

## Automated checks

**35 policy unit tests** covered URL parsing, deceptive domains, subdomains and ports, HTTPS upgrades, search domains, initial redirects, iframes, authentication-path manipulation, OAuth callback state, nested authentication, authentication expiry, and Firebase SDK event and callback formats.

**21 emulator browser integration tests** passed. The original seven checks verified the following:

1. A search link shows a blocked page without starting an engine session.
2. Links within the site open, while links to another host do not send a request.
3. Script-driven attempts to navigate to another site are blocked.
4. A popup opens and closes, returning to its parent page.
5. An OAuth callback with the expected address and state is delivered to an installed test app.
6. Custom Tabs binding, session creation, warmup, and page-completion callbacks work.
7. A Firebase SDK callback activity pinned to the installed app's certificate receives the matching event and encrypted `link` field; a wrong event is blocked.

The fourteen SMS checks cover waiting only on recognized verification screens, initial opt-in followed by automatic input and verification, identifying a labelled field without touching the phone field, single unlabelled code handling, ambiguous-code fallback, automatic waiting after navigation/app restart, rejecting stale messages, disabling the saved setting, background cancellation and automatic rearming, unrelated/wrong-host messages, split-input filling, malformed broadcasts, and consent results delivered before resume. These checks inject only the SMS body (or simulate the Android consent phase) and use the real GeckoView bridge and HTML forms. **Seven emulator update integration tests** also passed.

**11 SMS parser unit tests**, **22 JavaScript field/bridge tests**, **14 update unit tests**, **35 navigation unit tests**, and **9 release-publication tests** passed. The JavaScript checks include controlled form events and delayed button enablement, submitting exactly once, cancellation between input and submission, refusing payment/transfer controls, split widgets with edited/disabled digits, late-mounted widgets, and automatic retry detection. Both debug and optimized unsigned release APKs built and Android Lint finished with zero errors.

## SMS sign-in on the emulator

On Android 10–16, AIR uses a dynamically registered `SMS_RECEIVED_ACTION` receiver after the user's explicit opt-in and Android runtime `RECEIVE_SMS` grant. A single approval enables subsequent recognized verification screens; no per-message Android dialog or normal code/field selection is needed. The receiver is authenticated with `BROADCAST_SMS`, and message text is discarded after parsing. The merged manifest requests `RECEIVE_SMS` but has no `READ_SMS`, `SEND_SMS`, or notification-listener permission.

A fresh debug installation was also checked through the real Android runtime SMS permission dialog and emulator telephony broadcast. After the enable action and one permission Allow tap, `adb emu sms send` delivered a synthetic verification code **001234**. AIR filled it, activated the fixture's verification form and navigated to **AUTO_LOGIN_COMPLETE**, with no message/code/field/verification taps. After force-stopping and reopening another verification URL, the saved preference automatically armed reception; a second synthetic SMS completed the flow with zero taps after opening the page. No real carrier SMS or real service account was used.

Android 17 [protects OTP broadcasts](https://developer.android.com/about/versions/17/behavior-changes-17), including ordinary OTP messages for apps targeting API 37. AIR selects Google's SMS User Consent API on API 37 and later rather than waiting for broadcasts that may be delayed by three hours. The per-message Android Allow tap remains required in this mode, followed by automatic filling and verification. The previous implementation's real Google Play services consent Allow/Deny paths were checked on the API 35 emulator; the new paused-consent/result and automatic-submission paths have browser integration coverage. The actual Android 17 OS fallback still needs device verification.

The production feature is limited to HTTPS pages. Debug builds allow localhost/127.0.0.1 HTTP fixtures. The extension version is now **2.2** so GeckoView refreshes its persisted built-in scripts on upgrade. Old evidence in `artifacts/verification/sms-assist.txt` and `artifacts/screenshots/sms-auto-input.png` records the earlier consent-only, fill-only implementation. Current evidence is recorded separately in `artifacts/verification/sms-auto-login.txt`.

## Camera and screens

- The QR camera preview and torch button were checked after granting camera permission.
- A single QR code was supplied through the emulator's camera image input. The app decoded it through its camera path and opened `https://example.com/?source=air-qr`.
- Screenshots confirmed the QR-only home action and the web screen's read-only site information, back button, and finish button.
- Physical-device focus, low-light behavior, camera-permission changes, and manufacturer-specific background behavior still need testing.

## Not yet verified

Completed sign-ins using real Google, Kakao, Naver, banking, or other service accounts have not been tested. These checks cover the engine, browser protocols, navigation policy, and callbacks to other apps; they do not establish compatibility with every service. Custom SAML or native authentication flows, apps that specifically require Chrome, unfamiliar authentication or payment frames, and device-specific passkey behavior need testing with the actual apps and devices.

Carrier-delivered SMS on physical devices, manufacturer-specific behavior, and service-specific OTP forms still need testing. SMS codes received through RCS/email and embedded-frame fields are outside the current feature's scope. Split one-character widgets are supported when the complete 4–10-character group is identifiable and empty; unsupported/custom widgets retain manual input. Installer restrictions on `RECEIVE_SMS`, SMS Retriever messages bound to other apps, and the Android 17 consent fallback need physical-device coverage. The SMS extension's `manifest.json` version must be increased when changing its scripts so GeckoView's persisted built-in extension is refreshed on upgrade.

The Firebase adapter was implemented after inspecting the manifest and distributed class contracts in Google's [Firebase Auth Android SDK 25.0.0](https://dl.google.com/dl/android/maven2/com/google/firebase/firebase-auth/25.0.0/firebase-auth-25.0.0.aar). The test receiver reproduces the activity and intent contract. It does not substitute for verification using the real Firebase SDK and server. The SDK was not added as an AIR app dependency.

Logs are stored under `artifacts/verification/`. The checksums in `artifacts/SHA256SUMS` identify the local APKs used for this verification.
