# AIR — Only the connections you need

AIR is an Android browser that blocks harmful sites and holds back YouTube's recommendations: search shows Google SafeSearch text results only, adult sites and communities do not open, and YouTube's home feed is hidden while its Shorts and video-to-video recommendations are refused. Every other page opens normally, whatever led to it.

AIR has no address bar, search suggestions, tab list, bookmarks, or browsing-history screen. It adds no subscription, ads, account, usage rewards, or recommendation feed.

## Install

Download **AIR.apk** from the [latest AIR release](https://github.com/imjunseong958-droid/browser_air/releases/latest) and install it. Releases also include `SHA256SUMS` for verifying the download and `update.json` for the app's update checker. No GitHub sign-in is needed to download public release assets.

AIR supports **ARM64 (`arm64-v8a`) devices running Android 10 or later**. It no longer distributes x86_64 or 32-bit ARM APKs; use an ARM64 emulator for development.

Distributed APKs use a fixed release signing key and keep the application ID `dev.air.browser`. APK binaries and signing keys are excluded from Git. Pull requests verify debug and unsigned release builds without distributing APKs or accessing release credentials.

Transfer the APK to your device and install it. Android may ask you to allow installation from the app that opened the file.

Choose AIR as your default browser on first launch so other apps can send web links to it. You can also select it under **Settings → Apps → Default apps → Browser app → AIR**. The home screen shows a Bible verse in Korean and English, with **Search** below it and a small **Check for updates** button at the bottom. QR codes open through the phone's camera app. The app interface is currently in Korean.

AIR includes its own GeckoView browser engine, so its APK is larger than a typical WebView app. The engine works without Chrome installed. Some passkey features may be limited on devices without Google Play services.

## Updates

AIR release builds quietly check GitHub Releases when the home screen opens, at most once per 24 hours, and again right after an update installs. The footer button changes to **Update AIR [version]** when a newer compatible release is available. Tapping it, or **Check for updates**, first asks GitHub for the latest release and offers that one, so releases published in between are skipped rather than installed one by one. Confirm the download, see its progress, and open Android's installation confirmation. Automatic checks do not show popups or download APKs.

On the first in-app update, Android may require enabling **Allow from this source** for AIR. Return to AIR to continue installing the verified download. Android still asks you to approve installation. Closing the installation screen leaves the download available for retry. Network failures do not block QR scanning or browsing, and a completed download can be retried without downloading it again. Network work survives screen rotation and stops when the home screen is finished; interrupted downloads are not offered for installation.

Updates preserve app data, including settings and the Gecko profile containing cookies and site storage, when the package name and signing certificate match. AIR verifies the downloaded file's size, SHA-256, application ID, version, minimum Android version, ARM64 libraries, and installed signing certificate before offering it to Android. Downloads use HTTPS and are limited to this repository's release URLs and GitHub's release-asset hosts. The updater has no GitHub token and never exposes browser storage to the installer.

CI assigns each new main-branch workflow run a higher `versionCode` (`1000 + GITHUB_RUN_NUMBER`); rerunning an existing run keeps its version code. `airVersionName` in `gradle.properties` is the major and minor version; a release build numbers the last part from its version code, so versionCode `1052` is shown as `0.1.52`. Releases before this change were named like `0.1.3+1049`. Local builds without `AIR_VERSION_CODE` use `airVersionCode`, such as `0.1.3`.

**Enabling in-app updates:** releases made before this feature must be updated manually once by installing a newer `AIR.apk`. Subsequent releases can be downloaded and installed through AIR's update button. Manual APK installation remains available; do not uninstall AIR before updating an existing release.

**Migrating from the old test APKs:** previous CI runs generated a different debug key every time. A release cannot replace those installations without the original signing key. If that key is unavailable, one uninstall/reinstall is needed and clears the old app data. Subsequent AIR releases use the fixed release key and can update in place. Android's [signing](https://developer.android.com/studio/publish/app-signing#considerations) and [versioning](https://developer.android.com/studio/publish/versioning) documentation describe these requirements.

Publishing uses GitHub Releases, with no separately operated update server. CI first uploads all assets to a draft and then publishes it. Reruns preserve already published assets; a late older workflow cannot replace a newer release as the latest version. The Actions **AIR** artifact remains available as a build record.

## What it does

- Opens external HTTP/HTTPS links, including QR codes scanned with the phone's camera app when AIR is the default browser. AIR has no built-in QR scanner.
- Shows a different Bible verse each time AIR is opened, in Korean (개역한글) and English (World English Bible, American edition). Each install walks through its own shuffled order of the 149 verses in `app/src/main/assets/verses.tsv`, so none comes back until all have been shown. Turning the screen or closing a page opened from home keeps the verse. Links from other apps go straight to the page.
- **검색** on the home screen opens a spotlight-style search over the home screen. A typed query opens as Google results with `safe=active` and `udm=14` (text links only), from the keyboard's search key or the Google row. Any Google search link, including image, video, and AI tabs, is rewritten to that view before loading; other search engines stay blocked.
- On YouTube, the home page, search results, and videos open, but the home feed is hidden and subscriptions and Shorts do not open. Moving straight from one video to another (a recommendation, autoplay, or a `youtu.be` link) is refused unless both videos are in the same chosen playlist; YouTube's generated mixes (`list=RD…`) do not count. A small built-in extension (`app/src/main/assets/youtube-calm`) hides the recommended videos under a video and on its end screen, the home feed, Shorts shelves in search results, and the Home/Shorts/You bar. It also turns captions on in Korean for each video: a Korean track when there is one, otherwise YouTube's auto-translation of the video's captions (written captions first, English first, auto-generated captions included). Turning captions off or choosing another language in the player holds for that video.
- Checks every address change, not only page loads. Sites such as YouTube change their own address without loading a page; a change to a blocked route is undone by returning to the previous page.
- Resolves recognized HTTPS Google `/url` link wrappers before loading, checking the final destination and rejecting ambiguous or unsafe targets. It never opens the wrapper as an unchecked redirect.
- Opens every page not listed below, whether reached by a link, redirect, script, popup, or frame. There is no task or site boundary. Addresses browsers accept but `java.net.URI` does not, such as `?ids=1|2`, `/100%/off`, or a host with an underscore, are read the way a browser reads them.
- Applies the navigation policy to links, script-driven redirects, popups, and iframe documents. External images, scripts, and stylesheets can still load. Content a page makes itself (`about:blank`, `srcdoc`, `blob:`, `data:`) runs in the engine.
- Hands phone, mail, and other apps' links (`tel:`, `mailto:`, `kakaotalk:`, payment apps) to Android. A page that did not follow a tap has to ask first. From an `intent:` link only the address is used, never its package, extras, or fallback URL, and an `https` address loads inside AIR. Local schemes such as `file:` and `content:` are refused.
- Returns to the page behind when a link fails to load: **Back** closes the error, and **Retry** loads the failed link again.
- Blocks a fixed list of communities and their subdomains (for example DC Inside, FM Korea, theqoo, Ruliweb, Instiz, Clien, MLBPark, Nate Pann, Arca Live, and Reddit) on entry, links, redirects, popups, and frames. The list is `COMMUNITY_DOMAINS` in `navigation/src/main/java/dev/air/navigation/WebLinks.java`.
- Resolves web addresses only through Cloudflare's family DNS-over-HTTPS resolver (`family.cloudflare-dns.com`), which refuses adult and malware domains. There is no fallback to the device's DNS.
- Blocks adult sites on every route, including ones the family resolver lets through: a fixed list (`ADULT_DOMAINS` in `WebLinks.java`, for example nimi.wiki, the X video ranking known as 트위터닷넷 or 니미위키, and twitter.net, which is not X), any host containing a known adult site name such as `porn`, `xvideo`, or `yadong`, and the `.xxx`, `.porn`, `.sex`, and `.adult` domains. Only the host is checked, not the path.
- Blocks search that SafeSearch does not cover and the recommendation feeds, by destination and route: Google image, video, shopping, and Discover pages, Bing, DuckDuckGo and other engines, Naver/Daum search, YouTube feed/Shorts, and X home/explore/search, where adult videos circulate. Google's home page and the Naver and Daum home pages stay open. The block also applies to external entries, wrappers, redirects, and frames.
- Provides an Android Custom Tabs service so clients such as AppAuth can select AIR for authentication. It reports page-start, completion, failure, and close events to the calling app.
- Sends an OAuth/OIDC result back to the app that opened AIR for it. The request is recognized from its `redirect_uri`, `client_id`, and state in the first page's redirect chain, and the result must match the callback's scheme, host, port, path, fixed query parameters, and state. A sign-in a page starts itself is an ordinary move between pages.
- Handles Firebase Android SDK callbacks for OAuth and phone-auth verification. It checks the requested app package, installed signing-certificate fingerprint, SDK callback activity, and `eventId`, then forwards only the required callback fields. The installed signature is checked again on return. AIR does not bundle the Firebase SDK or Firebase analytics.
- Identifies Custom Tabs callers through their Binder UID. HTTPS callbacks are delivered to the verified calling app or completed inside AIR. There is no button to hand a blocked page to another browser.
- Preserves `window.opener` and `postMessage` connections for login popups. One session is visible at a time, with at most two additional popup sessions; a further new-window link opens in the page showing now.
- Supports JavaScript dialogs, choice forms, HTTP authentication, date inputs, file uploads, and FedCM account selection. File downloads use the engine's authenticated response stream, including files a page makes itself, keep the name the server or page chose, and are limited to 100 MB.
- Requests camera, microphone, and location permissions when needed, checking both Android permissions and the requesting site's permission. Storage access for embedded sign-ins is allowed. Notification, autoplay, and tracking-exception requests are denied.
- Shows pages full screen with no header; only a thin loading line appears at the top. Android's back gesture goes back a page, closes a popup, and closes AIR from the first page.
- Reloads the page when you pull down from its top and let go past a turning arrow. Only a pull the page itself would overscroll counts: a page scrolled down or zoomed in, a two-finger pinch, a page that handles the touch itself (a map, a video player), a page that turns overscroll off, and a video's full screen do not reload.
- Lets you pinch to zoom on every site, including ones such as YouTube that switch zoom off. Upright, the status bar keeps its strip above the page. Turning the phone sideways hides the system bars so the page fills the screen, and a video's full screen also uses the area beside a camera cutout and turns the screen sideways (a portrait video stays upright); a swipe from the edge shows the bars for a moment, and back leaves full screen first.

## Compatibility and limits

AIR does not read SMS messages or fill verification codes. Type the code on the site, or use your keyboard's suggestion, such as the clipboard chip Gboard shows after tapping **Copy code** in a message notification. Release-manifest generation rejects APK metadata that declares SMS receive/read/send permissions, a known sensitive-permission condition in [Google Play Protect's guidance for sideloaded apps](https://developers.google.com/android/play-protect/warning-dev-guidance).

**Real-service login compatibility has not been verified across all providers.** Google, Kakao, Naver, and banking apps still need testing with real accounts on physical devices. Apps that require a specific Chrome package or signature, custom SAML or native authentication flows, unfamiliar CAPTCHA or payment domains, DRM, and provider-specific browser policies may need additional implementation or testing. AIR does not advertise unverified support for Auth Tab, Trusted Web Activities, Digital Asset Links, or Custom Tabs postMessage.

SafeSearch, the family resolver, and the adult-site rules reduce, but cannot eliminate, adult content: they do not filter images or posts inside otherwise allowed sites, and an adult site or community that is not on a list and does not carry a known name opens like any other site. A name rule can also refuse an unrelated site whose host happens to contain one of those names. If the resolver is unreachable (for example on a network that blocks DNS-over-HTTPS), pages fail to load instead of falling back to unfiltered DNS.

Apart from YouTube's hiding rules, AIR does not inspect and remove a site's own search, recommendations, or feeds from its page. Known route rules cannot identify every search, feed, or proxy site. Opening a new link from another app can start a new session. The navigation limits apply within AIR; other apps on the device remain usable, and a link a page hands to another app, such as another browser, leaves these limits.

HTTP links are supported for uses such as restaurant menus, with an unencrypted-connection notice in the site information. Invalid TLS certificates cannot be bypassed.

## Privacy

AIR has no app-owned analytics SDK, advertising SDK, server, or account system, and does not add URL or authentication-code logging. Session lists and navigation policies live in memory. Cookies and site storage needed for sign-in are kept in the device's Gecko profile and can be removed by clearing app data or uninstalling AIR. Cloud backup and device-to-device data transfer are disabled.

Websites and the browser engine make network requests needed for normal operation. Web address lookups go to Cloudflare's family resolver over HTTPS instead of the network's DNS server. Search terms go to Google as an ordinary search request; AIR keeps no search history. Closing a page does not clear all cookies. Password saving is disabled, while Android input support, passkeys, and web sign-in cookies remain available where supported.

The updater requests public version metadata and, after confirmation, APK files from GitHub. GitHub receives ordinary connection information such as the IP address. AIR sends no browsing URLs, sign-in cookies, account data, or device identifiers in these requests. Update-check timing and the last valid release manifest are stored locally; APKs are held in a private, dedicated cache directory. Debug builds skip automatic release checks because their signing key differs from the release channel.

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

Open this folder in Android Studio, or install JDK 17 and the Android SDK, then run:

```sh
bash scripts/build.sh
```

Set `ANDROID_HOME` or the `sdk.dir` entry in `local.properties` to your SDK location. The script can use local runtimes under `.tools/` when available. Its default debug build generates a local debug signing key and writes `artifacts/debug/AIR.apk`; use that APK only for development. It is not interchangeable with the release APK. Keep the browser engine updated for security, and rerun authentication, navigation-policy, and device checks when changing engine versions.

- `navigation/`: URL parsing, the navigation policy, sign-in recognition, and unit tests independent of Android.
- `app/`: native screens, browser sessions, Custom Tabs, and callbacks to other apps.
- `app/src/main/assets/verses.tsv`: the home verses, one per line: Korean reference, English reference, Korean text, English text.
- `app/src/main/assets/youtube-calm/`: the YouTube hiding rules (`hide.css`) and Korean captions (`captions.js`). GeckoView keeps installed built-in extensions, so raise `version` in its `manifest.json` whenever either file changes.
- `updates/`: release URL and version policy, bounded HTTPS transfers, checksum checks, and JVM regression tests.
- `.github/workflows/android.yml`: checks pull requests and builds signed ARM64 releases on main, running Android Lint, policy tests, and publication tests before uploading the **AIR** artifact. A separate main-only job with repository write permission publishes GitHub Releases.

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

CI generates `update.json` from the actual APK using `scripts/release_manifest.py`, then `scripts/publish_release.py` publishes `AIR.apk`, `SHA256SUMS`, and `update.json` under `air-v[versionCode]`. The app reads the public `releases/latest/download/update.json` URL; the manifest points to an immutable APK URL for that exact version. Python 3 and the GitHub CLI are needed for publication. Run the publication regression tests without publishing anything:

```sh
python3 -m unittest discover -s scripts/tests -v
```

After building with the script above, connect an Android device or start an emulator to run the instrumented tests:

```sh
./gradlew :navigation:test :updates:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
./gradlew :app:connectedDebugAndroidTest
```

Verification uses a local HTTP server and test callback receivers without real accounts. It covers blocked entries, links to other sites, unusual addresses, script redirects, popups, other apps' links, frames, downloads, load failures, the home verse, Custom Tabs, OAuth callbacks, and Firebase certificate, event, and encrypted-field handling. See the [verification record](docs/verification.md) for results and remaining checks.

## Third-party components

GeckoView is distributed under the Mozilla Public License 2.0, and AndroidX uses Apache License 2.0. AIR consumes an unmodified GeckoView engine from Maven. Component notices and original-source references are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
