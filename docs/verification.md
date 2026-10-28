# Verification record

Initial browser verification was performed on **14 October 2026 (Asia/Seoul)**. SMS automatic sign-in was completed and checked on **15 October 2026 (Asia/Seoul)** using an ARM64 Pixel 7 emulator running Android 15 (API 35). The app was built for Android 10 and later. No real user accounts or personal login credentials were used.

## SMS sign-in removed — 28 October 2026

Automatic SMS sign-in was removed: the built-in extension, consent receiver, code parser, footer toggle, and the Play services SMS dependency. Earlier entries that mention SMS describe the removed feature; their detailed records remain in Git history.

- **45 navigation/Firebase tests**, **14 update tests**, and **10 release-publication tests** passed, including the check that rejects APKs declaring SMS receive/read/send permissions.
- Debug and optimized unsigned release builds passed; both Lint variants reported zero errors. Neither APK contains SMS assets or requests SMS permissions.
- **16 Android 15 / API 35 emulator integration tests** passed: nine browser and seven updater tests.

## Task boundaries and one-message consent — 15 October 2026

This implementation replaced an earlier direct-SMS receiver. All supported Android versions used Google Play services SMS User Consent, and the production manifest has no SMS receive/read/send permission.

- **51 navigation/Firebase tests**, **14 update tests**, **11 SMS parser tests**, **22 JavaScript field/bridge tests**, and **10 release-publication tests** passed. Both debug and optimized release builds and Android Lint passed.
- **34 emulator integration checks** passed: 27 browser/SMS checks and seven updater checks. This includes wrapper resolution, choosing/cancelling an external continuation, background cancellation, external popup handling, existing OAuth/Firebase callbacks, refusal to fill before consent, denied consent, stale/wrong-host results, and automatic filling/submission after an approved result.
- A real Google Play services one-message consent dialog was checked on the API 35 emulator using a synthetic incoming SMS. The OTP field remained empty before approval. Tapping **Allow** once filled `001234`, submitted the local verification form, and displayed `CONSENT_SMS_COMPLETE`, without further code/field/verify taps.
- The signed **0.1.3+1017** APK was checked against the previous public **0.1.2+1016** release: the signing certificate is identical, and APK metadata declares no `RECEIVE_SMS`, `READ_SMS`, or `SEND_SMS`. Release-manifest generation also rejects APK metadata containing any of those permissions.
- An in-place install of the signed 1017 APK over the public 1016 APK succeeded on the emulator. A local fixture's `localStorage` value and persistent cookie (`Max-Age=3600`) survived the update. Session cookies are not a persistence guarantee across app-process restarts. The user-supplied Forms short link also resolved and displayed its application form in the installed release, without submitting a response or signing in.

Evidence for this run is summarized in `artifacts/verification/task-boundary-consent.txt`. Detailed local build/device logs and synthetic UI captures are under `.tools/task-boundary-*` (ignored by Git).

The affected Samsung device is not connected. Removing SMS permissions addresses a documented Play Protect condition; it does not establish that a particular manufacturer warning has disappeared. Installation and the next in-app update still require verification on that phone. Real account sign-ins across providers and Android versions remain outside this emulator check.

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

## Camera and screens

- The QR camera preview and torch button were checked after granting camera permission.
- A single QR code was supplied through the emulator's camera image input. The app decoded it through its camera path and opened `https://example.com/?source=air-qr`.
- Screenshots confirmed the QR-only home action and the web screen's read-only site information, back button, and finish button.
- Physical-device focus, low-light behavior, camera-permission changes, and manufacturer-specific background behavior still need testing.

## Not yet verified

Completed sign-ins using real Google, Kakao, Naver, banking, or other service accounts have not been tested. These checks cover the engine, browser protocols, navigation policy, and callbacks to other apps; they do not establish compatibility with every service. Custom SAML or native authentication flows, apps that specifically require Chrome, unfamiliar authentication or payment frames, and device-specific passkey behavior need testing with the actual apps and devices.

The Firebase adapter was implemented after inspecting the manifest and distributed class contracts in Google's [Firebase Auth Android SDK 25.0.0](https://dl.google.com/dl/android/maven2/com/google/firebase/firebase-auth/25.0.0/firebase-auth-25.0.0.aar). The test receiver reproduces the activity and intent contract. It does not substitute for verification using the real Firebase SDK and server. The SDK was not added as an AIR app dependency.

Logs are stored under `artifacts/verification/`. The checksums in `artifacts/SHA256SUMS` identify the local APKs used for this verification.
