# Verification record

Initial browser verification was performed on **14 October 2026 (Asia/Seoul)**. SMS input assistance was completed and checked on **15 October 2026 (Asia/Seoul)** using an ARM64 Pixel 7 emulator running Android 15 (API 35). The app was built for Android 10 and later. No real user accounts or personal login credentials were used.

## Build and installation

- Gradle build and Android Lint completed with zero errors. Some dependency-update and layout recommendations remain as warnings.
- The current ARM64 debug APK passed signature verification and was installed in the emulator. The unsigned, optimized release APK also built successfully; this check does not publish an update. Earlier x86_64 test APKs are no longer distributed.
- `dev.air.browser` was successfully registered for Android's default browser role.
- The independent browser engine and authentication callbacks were checked with Chrome disabled in the emulator.

## Automated checks

**35 policy unit tests** covered URL parsing, deceptive domains, subdomains and ports, HTTPS upgrades, search domains, initial redirects, iframes, authentication-path manipulation, OAuth callback state, nested authentication, authentication expiry, and Firebase SDK event and callback formats.

**15 emulator browser integration tests** passed. The original seven checks verified the following:

1. A search link shows a blocked page without starting an engine session.
2. Links within the site open, while links to another host do not send a request.
3. Script-driven attempts to navigate to another site are blocked.
4. A popup opens and closes, returning to its parent page.
5. An OAuth callback with the expected address and state is delivered to an installed test app.
6. Custom Tabs binding, session creation, warmup, and page-completion callbacks work.
7. A Firebase SDK callback activity pinned to the installed app's certificate receives the matching event and encrypted `link` field; a wrong event is blocked.

The eight added SMS checks covered opt-in and standard-field filling without submitting, explicit selection of ambiguous codes and unmarked fields, discarding an old consent result after navigation, cancellation/backgrounding, rejecting codes bound to another host, manual fallback for split fields, and ignoring malformed SMS broadcasts. These automated checks simulate the successful Android consent result and use the real GeckoView content bridge.

**11 SMS parser unit tests**, **8 JavaScript field/bridge tests**, **14 update unit tests**, and **9 release-publication tests** also passed. The parser checks leading zeroes, Korean/English and alphanumeric codes, ambiguous candidates, domain binding, and avoiding phone/date/amount fragments. Field tests cover hidden/inert/edited inputs, form events, request/document/URL matching, cancellation, and back-navigation reconnects. Both debug and release Android Lint finished with zero errors (21 dependency/layout warnings).

## SMS consent on the emulator

The real SMS User Consent API was also checked with Google Play services **24.23.35** in the emulator, without a mock SMS client. A synthetic SMS was delivered through the emulator console to a local test page forwarded with `adb reverse`. The Android dialog asked whether AIR could read that one message. Selecting **Allow** returned to AIR and filled **001234**, preserving the leading zeroes. Selecting **Deny** for a second message left the input empty and ended the wait. Neither path sent a real carrier SMS or used a real service account.

The production feature is limited to HTTPS pages. Debug builds allow localhost/127.0.0.1 HTTP fixtures for testing. The merged app manifest contains no `READ_SMS`, `RECEIVE_SMS`, or notification-listener permission. APK inspection confirmed that the packaged SMS scripts match the checked source and that the APK contains only ARM64 native libraries. See `artifacts/screenshots/sms-auto-input.png` and `artifacts/verification/sms-assist.txt` for evidence.

## Camera and screens

- The QR camera preview and torch button were checked after granting camera permission.
- A single QR code was supplied through the emulator's camera image input. The app decoded it through its camera path and opened `https://example.com/?source=air-qr`.
- Screenshots confirmed the QR-only home action and the web screen's read-only site information, back button, and finish button.
- Physical-device focus, low-light behavior, camera-permission changes, and manufacturer-specific background behavior still need testing.

## Not yet verified

Completed sign-ins using real Google, Kakao, Naver, banking, or other service accounts have not been tested. These checks cover the engine, browser protocols, navigation policy, and callbacks to other apps; they do not establish compatibility with every service. Custom SAML or native authentication flows, apps that specifically require Chrome, unfamiliar authentication or payment frames, and device-specific passkey behavior need testing with the actual apps and devices.

Carrier-delivered SMS on physical devices, manufacturer-specific behavior, and service-specific OTP forms still need testing. SMS codes received through RCS/email, embedded-frame fields, and separate one-character input boxes are outside the current feature's scope. The SMS extension's `manifest.json` version must be increased when changing its scripts so GeckoView's persisted built-in extension is refreshed on upgrade.

The Firebase adapter was implemented after inspecting the manifest and distributed class contracts in Google's [Firebase Auth Android SDK 25.0.0](https://dl.google.com/dl/android/maven2/com/google/firebase/firebase-auth/25.0.0/firebase-auth-25.0.0.aar). The test receiver reproduces the activity and intent contract. It does not substitute for verification using the real Firebase SDK and server. The SDK was not added as an AIR app dependency.

Logs are stored under `artifacts/verification/`. The checksums in `artifacts/SHA256SUMS` identify the local APKs used for this verification.
