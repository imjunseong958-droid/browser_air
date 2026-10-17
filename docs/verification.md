# Verification record

Initial browser verification was performed on **14 October 2026 (Asia/Seoul)**. SMS automatic sign-in was completed and checked on **15 October 2026 (Asia/Seoul)** using an ARM64 Pixel 7 emulator running Android 15 (API 35). The app was built for Android 10 and later. No real user accounts or personal login credentials were used.

## SMS automatic input recovery — 18 October 2026

The reported flow should remain automatic after Google's per-message Allow. The earlier field chooser is retained only for ambiguous cases. No SMS permission or consent behavior changes.

The detector excludes personal name fields even when their identifiers contain authentication words, recognizes phone-prefixed code identifiers, and uses the approved-message context to infer one otherwise unnamed or length-limited code control. Phone, password, CAPTCHA, birth-date and coupon fields remain excluded from that inference. A request-code button disappearing within the same document no longer discards the active consent window.

A single rejected input is automatically retried through GeckoView's Android input connection. This also handles a value that stays visible while the site ignores synthetic events and leaves the verification button disabled. Both the extension and Android editor check the value before retrying; other user input is preserved. For an unchanged code, the editor removes only that exact code before recommitting it, because an identical replacement can suppress input events. Recovery stays in the original document/form, runs once, and is cancelled on navigation/backgrounding. Android operations use the input connection's own handler; queued writes check a cancellation flag before editing and acknowledge only the current foreground request. The native acknowledgement is never taken as proof of successful input: completion reads the live control and checks it after a verification click as well. If submission removes or hides the OTP widget, AIR ends quietly without claiming that login succeeded. Codes are not logged or copied to the clipboard. Extension version **2.4** refreshes installed profiles.

Five new regression cases fail against the previous main-branch scripts: phone-prefixed detection/bridge selection, unnamed fields beside a name field, a click that clears the code, and a submission fired after rejection. These are reproducible compatibility failures; they do not establish the exact DOM of the user's post-SMS Coupang screen.

Validation completed locally:

- **50 JavaScript field/bridge tests**, **76 Java unit tests** (51 navigation/Firebase, 14 update, 11 SMS parser), and **10 release-publication tests** passed.
- **23 Android 15 / API 35 SMS and permission cases** passed, including both native keyboard recovery paths, unnamed inputs beside a name, consent denial, stale/wrong-host messages, pause/resume and split fields. Two timing assertions were updated to await the new post-submit acknowledgement and rerun successfully. Both native recovery cases were also repeated successfully with the editor handler, including an explicitly forced background input thread.
- Debug and optimized unsigned release APKs built; both Lint variants reported zero errors. Both APKs contain the exact checked SMS sources and declare no SMS read/receive/send permissions.

The public Coupang phone-login entry was inspected without credentials or requesting a message. The post-SMS Coupang page, carrier delivery, real account login and the affected physical phone remain untested.

## SMS consent recovery — 17 October 2026

The extension now waits up to ten seconds for the foreground OTP widget, checks that inserted values survive controlled-input updates, and retries a cleared/replaced widget once within its original form/group. It waits up to eight seconds for completion and a unique verification button, including nearby buttons in formless layouts. User edits and request cancellation stop completion; ambiguous or sensitive actions remain manual. Native response timeouts allow fifteen seconds per phase and show the approved code in a short-lived fallback dialog instead of silently losing it. A filled code without a submission attempt prompts the user to finish on the site. A button click is a verification attempt, not a claim that login succeeded.

- **37 JavaScript field/bridge tests**, **76 Java unit tests** (51 navigation/Firebase, 14 update, 11 SMS parser), and **10 release-publication tests** passed. Regressions cover synchronous/asynchronous resets, repeated rejection, replaced widgets, user-edited values, cancellation, delayed widgets/buttons, formless controls, alternative login methods, and payment controls introduced while waiting.
- **37 Android 15 / API 35 emulator integration tests** passed (30 browser/SMS, seven updater), including approved-result recovery across pause/resume, a field enabled three seconds after requesting SMS, a verification button enabled 2.5 seconds after input, replacement-field recovery, and manual fallback after repeated rejection. Initial runs were interrupted by unrelated system-app ANR dialogs; the successful run used a fresh ARM64 Pixel 7 AVD.
- Debug and optimized unsigned release APKs built; both Lint variants reported zero errors. Both packaged SMS scripts match the final sources, and actual APK metadata contains no SMS read/receive/send permission. Local logs are under `.tools/sms-consent-recovery-*` (ignored by Git).
- The built-in extension version advances to **2.3** so installed profiles refresh the scripts. SMS receive/read/send permissions are still absent.

This change follows a reported Coupang login failure. The public phone-login entry was inspected, but the post-SMS Coupang page, real carrier delivery, account login, and the affected physical phone were not exercised. These tests do not establish that the particular Coupang flow is resolved.

## Task boundaries and one-message consent — 15 October 2026

The current implementation replaces the earlier direct-SMS behavior described below. All supported Android versions use Google Play services SMS User Consent, and the production manifest has no SMS receive/read/send permission.

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

## Previous direct-SMS verification (superseded)

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

Carrier-delivered SMS on physical devices, manufacturer-specific behavior, and service-specific OTP forms still need testing. SMS codes received through RCS/email and embedded-frame fields are outside the current feature's scope. Split one-character widgets are supported when the complete 4–10-character group is identifiable and empty; unsupported/custom widgets retain manual input. The current one-message consent implementation needs physical-device coverage on all supported Android versions, including eligible-message and Google Play services behavior. The SMS extension's `manifest.json` version must be increased when changing its scripts so GeckoView's persisted built-in extension is refreshed on upgrade.

The Firebase adapter was implemented after inspecting the manifest and distributed class contracts in Google's [Firebase Auth Android SDK 25.0.0](https://dl.google.com/dl/android/maven2/com/google/firebase/firebase-auth/25.0.0/firebase-auth-25.0.0.aar). The test receiver reproduces the activity and intent contract. It does not substitute for verification using the real Firebase SDK and server. The SDK was not added as an AIR app dependency.

Logs are stored under `artifacts/verification/`. The checksums in `artifacts/SHA256SUMS` identify the local APKs used for this verification.
