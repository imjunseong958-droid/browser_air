# AIR — Only the connections you need

AIR is an Android app for QR links, web sign-ins, and links opened from other apps. It keeps practical web features available while limiting habitual browsing: each session stays within the opened site, with bounded exceptions for authentication.

AIR has no address bar, search entry point, tab list, bookmarks, or browsing-history screen. It adds no subscription, ads, account, usage rewards, or recommendation feed.

## Install

Download the **AIR** artifact from a successful **main-branch** [Android build run](https://github.com/imjunseong958-droid/browser_air/actions/workflows/android.yml), extract it, and install **AIR.apk**. The archive also includes `SHA256SUMS` for verifying the download.

AIR supports **ARM64 (`arm64-v8a`) devices running Android 10 or later**. It no longer distributes x86_64 or 32-bit ARM APKs; use an ARM64 emulator for development.

Distributed APKs use a fixed release signing key and keep the application ID `dev.air.browser`. APK binaries and signing keys are excluded from Git. Pull requests verify debug and unsigned release builds without distributing APKs or accessing release credentials.

Transfer the APK to your device and install it. Android may ask you to allow installation from the app that opened the file.

Choose AIR as your default browser on first launch so other apps can send web links to it. You can also select it under **Settings → Apps → Default apps → Browser app → AIR**. The only action on the home screen is **Scan QR code**. The app interface is currently in Korean.

AIR includes its own GeckoView browser engine, so its APK is larger than a typical WebView app. The engine works without Chrome installed. Some passkey features may be limited on devices without Google Play services.

## Updates

Download and install the newer `AIR.apk` over the installed release. Do not uninstall AIR first: Android preserves app data, including settings and the Gecko profile containing cookies and site storage, when the package name and signing certificate match. CI assigns each new main-branch workflow run a higher `versionCode` (`1000 + GITHUB_RUN_NUMBER`); rerunning an existing run keeps its version code. The user-visible version is maintained as `airVersionName` in `gradle.properties`.

**Migrating from the old test APKs:** previous CI runs generated a different debug key every time. A release cannot replace those installations without the original signing key. If that key is unavailable, one uninstall/reinstall is needed and clears the old app data. Subsequent AIR releases use the fixed release key and can update in place. Android's [signing](https://developer.android.com/studio/publish/app-signing#considerations) and [versioning](https://developer.android.com/studio/publish/versioning) documentation describe these requirements.

Downloading and installing updates is manual. AIR does not yet check for updates or install them from inside the app.

## What it does

- Opens external HTTP/HTTPS links and scans QR codes with the camera.
- Resolves up to eight initial HTTP redirects, including shortened QR links, then pins the session to the final site once the first document is displayed.
- Allows pages and forms on the same host and its `www` alias. Other subdomains, other ports, and HTTPS-to-HTTP transitions are treated as a different site.
- Applies the navigation policy to links, script-driven redirects, popups, and iframe documents. External images, scripts, and stylesheets can still load.
- Blocks known search and portal entry points, including Google, Bing, Naver Search, and Daum Search, even when opened through an external link or QR code. CAPTCHA frames receive limited exceptions.
- Provides an Android Custom Tabs service so clients such as AppAuth can select AIR for authentication. It reports page-start, completion, failure, and close events to the calling app.
- Tracks OAuth/OIDC requests using `redirect_uri`, `client_id`, and state values. External authentication started within a site must return to that site or an existing authentication transaction. Nested identity-provider flows preserve the original app callback and state.
- Checks callback schemes, hosts, ports, paths, fixed query parameters, and the state value when supplied. Authentication exceptions are bounded to 15 minutes, eight authentication origins, and four nested transactions. Arbitrary `intent:` packages and browser fallback URLs are not executed.
- Handles Firebase Android SDK callbacks for OAuth and phone-auth verification. It checks the requested app package, installed signing-certificate fingerprint, SDK callback activity, and `eventId`, then forwards only the required callback fields. The installed signature is checked again on return. AIR does not bundle the Firebase SDK or Firebase analytics.
- Identifies Custom Tabs callers through their Binder UID. HTTPS callbacks are delivered to the verified calling app or completed inside AIR. There is no button to hand a blocked page to another browser.
- Preserves `window.opener` and `postMessage` connections for login popups. One session is visible at a time, with at most two additional popup sessions.
- Supports JavaScript dialogs, choice forms, HTTP authentication, date inputs, file uploads, and FedCM account selection. File downloads use the engine's authenticated response stream and are limited to 100 MB.
- Requests camera, microphone, and location permissions when needed, checking both Android permissions and the requesting site's permission. Notification, autoplay, and tracking-exception requests are denied.
- Displays the current site in a read-only header. Tap it to inspect the full URL and connection security. **Finish** closes the page and popups and returns to the previous screen.

## Compatibility and limits

**Real-service login compatibility has not been verified across all providers.** Google, Kakao, Naver, and banking apps still need testing with real accounts on physical devices. Apps that require a specific Chrome package or signature, custom SAML or native authentication flows, unfamiliar CAPTCHA or payment domains, DRM, and provider-specific browser policies may need additional implementation or testing. AIR does not advertise unverified support for Auth Tab, Trusted Web Activities, Digital Asset Links, or Custom Tabs postMessage.

AIR does not inspect and remove a site's own search, recommendations, or feeds from its DOM. Its search-domain blocklist cannot cover every search or proxy site. Opening a new link from another app can start a new session. The navigation limits apply within AIR; other apps on the device remain usable.

HTTP links are supported for uses such as restaurant menus, with an unencrypted-connection notice in the site information. Invalid TLS certificates cannot be bypassed.

## Privacy

AIR has no app-owned analytics SDK, advertising SDK, server, or account system, and does not add URL or authentication-code logging. Session lists and navigation policies live in memory. Cookies and site storage needed for sign-in are kept in the device's Gecko profile and can be removed by clearing app data or uninstalling AIR. Cloud backup and device-to-device data transfer are disabled.

Websites and the browser engine make network requests needed for normal operation. Closing a page does not clear all cookies. Password saving is disabled, while Android input support, passkeys, and web sign-in cookies remain available where supported.

## Development

The application uses **Java 17**, **Android Views**, and **GeckoView**. GeckoView handles web rendering and execution.

| Component | Version |
|---|---|
| Android Gradle Plugin | 9.4.1 |
| Gradle | 9.6.0 |
| compileSdk | 37.2 |
| targetSdk / minSdk | 37 / 29 |
| GeckoView | 158.0.20261007201254 |
| AndroidX Browser | 1.10.0 |
| ZXing Android Embedded | 4.3.0 |

Open this folder in Android Studio, or install JDK 17 and the Android SDK, then run:

```sh
bash scripts/build.sh
```

Set `ANDROID_HOME` or the `sdk.dir` entry in `local.properties` to your SDK location. The script can use local runtimes under `.tools/` when available. Its default debug build generates a local debug signing key and writes `artifacts/debug/AIR.apk`; use that APK only for development. It is not interchangeable with the release APK. Keep the browser engine updated for security, and rerun authentication, navigation-policy, and device checks when changing engine versions.

- `navigation/`: URL parsing, authentication and site-navigation policies, and unit tests independent of Android.
- `app/`: native screens, QR scanning, browser sessions, Custom Tabs, and callbacks to other apps.
- `.github/workflows/android.yml`: checks pull requests and builds signed ARM64 releases on main, running Android Lint and policy tests before uploading the **AIR** artifact.

### Release signing

Create a release keystore **once**, then back up the keystore and passwords outside the checkout. Use that same key for every release; do not regenerate it in CI. Store the following repository Actions secrets:

| Secret | Value |
|---|---|
| `AIR_KEYSTORE_BASE64` | Base64-encoded bytes of the fixed release keystore |
| `AIR_KEYSTORE_PASSWORD` | Keystore password |
| `AIR_KEY_ALIAS` | Signing key alias |
| `AIR_KEY_PASSWORD` | Signing key password |

For a local release, export `AIR_KEYSTORE_PATH` (the keystore file path), `AIR_KEYSTORE_PASSWORD`, `AIR_KEY_ALIAS`, and `AIR_KEY_PASSWORD`. Set `AIR_VERSION_CODE` to an integer higher than the installed release when making an update, then run:

```sh
bash scripts/build.sh release
```

The output is `artifacts/release/AIR.apk` with `SHA256SUMS`. Keep local version codes consistent with CI; an artificially high local code can prevent installation of subsequent CI builds. Missing signing credentials stop the release script before it publishes any APK. A direct Gradle `assembleRelease` without credentials creates an unsigned APK for verification only. Release credentials are used only by main-branch CI runs, are restored to a temporary file, and are removed when the job finishes.

After building with the script above, connect an Android device or start an emulator to run the instrumented tests:

```sh
./gradlew :navigation:test :app:lintDebug :app:assembleDebugAndroidTest
./gradlew :app:connectedDebugAndroidTest
```

Verification uses a local HTTP server and test callback receivers without real accounts. It covers external-site blocking, navigation within a site, script redirects, popups, Custom Tabs, OAuth callbacks, and Firebase certificate, event, and encrypted-field handling. QR decoding was also checked with an image supplied to the emulator camera. See the [verification record](docs/verification.md) for results and remaining checks.

## Third-party components

GeckoView is distributed under the Mozilla Public License 2.0, and AndroidX and ZXing use Apache License 2.0. AIR consumes an unmodified GeckoView engine from Maven. Component notices and original-source references are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
