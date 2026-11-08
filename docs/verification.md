# Verification record

Initial browser verification was performed on **14 October 2026 (Asia/Seoul)**. SMS automatic sign-in was completed and checked on **15 October 2026 (Asia/Seoul)** using an ARM64 Pixel 7 emulator running Android 15 (API 35). The app was built for Android 10 and later. No real user accounts or personal login credentials were used.

## Sideways full screen and Korean captions — 8 November 2026

Sideways, the browser hid nothing: the status bar's white strip stayed above the page and shrank YouTube's video. Turning the phone sideways now hides the system bars and fills the screen; only a camera cutout keeps a black strip. A video's full screen fills the cutout area too. Upright pages keep the status bar strip.

The YouTube extension (version 1.2) gains `captions.js`, which turns captions on in Korean for each video through the player's own caption API: a Korean track when there is one, otherwise YouTube's auto-translation to Korean. It acts once per video and gives up after about 10 seconds when a video has no captions.

The track choice was checked with Node against stub players. YouTube, the Android SDK, and Mozilla's Maven repository are not reachable from this machine, so the build was left to CI and neither change was run in GeckoView or on a device.

## YouTube opens again — 8 November 2026

YouTube's home page shared the "other search engines" refusal, and the row that opened YouTube's search results left the search card on 7 November, so a link to YouTube was refused. The home page now opens with its feed hidden; subscriptions, Shorts, and video-to-video moves stay blocked. The hiding rules also cover the whole recommendation block under a video and the player's end screen.

The rules were checked by adding them to live `m.youtube.com` pages in the desktop app's browser pane (Chromium, phone size, signed out), not in GeckoView. On a video page all 72 links to other videos stopped being visible, while the video, title, and comments stayed. The home page has the `FEwhat_to_watch` tab the rule targets, but signed out it listed no videos, so hiding a filled feed was not seen. The player's end-screen rules were not seen working. This machine has no JDK or emulator, so the tests were left to CI and nothing was run on a device.

## Legacy task blocking removed, home verses, and a quieter search — 7 November 2026

AIR had moved to blocking harmful sites and holding back YouTube's recommendations, but the earlier "one task" rules were still refusing ordinary use, mostly with the message 지금 작업과 연결되지 않은 이동이에요. The task boundary, its 15-minute sign-in limits, and the work-only blocks on Google Docs and Drive lists, GitHub search, and the Google, Naver, and Daum home pages are gone. Adult sites, communities, unfiltered search, X feeds, and the YouTube rules are unchanged. The YouTube row left the search card, and home now shows a Bible verse in Korean and English.

New tests were run against the code before this change first, with one control test (an ordinary link) that passed:

| Failed before the change | Cause |
|---|---|
| A third new-window link did nothing | The popup limit refused it as an unrelated move |
| Links such as `?ids=1\|2`, `?q={x}`, `?q=a^b`, and `#/route\|x` | `java.net.URI` rejected them, which was treated as an unrelated move |
| An app link, tested with a custom scheme; `tel:`, mail, and payment apps take the same path | Every non-web scheme was treated as an unrelated move |
| A `srcdoc` frame, the first checked; `blob:` and `data:` frames take the same path | Refused without a message, because they have no web address |
| Back after a failed link closed everything | The error screen assumed one task |
| Retry after a failed link went to the first page | Same assumption |

Saving a file a page makes itself also failed, for two reasons: a trace showed the `blob:` load request reaching the policy, which the old code refused, and then its `statusCode=0` being treated as a failed download. The test's failure before the change came from a mistake in its own page, so the old refusal rests on the trace and code reading. The test passes now.

Google link wrappers with an empty `q` next to `url` and hosts with an underscore were also refused. These were found by reading the code and a parser check on the JVM, and no page loaded through them was tested.

- **41 navigation/Firebase tests** and **14 update tests** passed, along with five verse tests covering every bundled verse, the shuffled order, and consecutive launches differing. Debug and optimized release builds passed; both Lint variants reported zero errors. Release-publication tests passed.
- **23 browser and seven updater integration tests** passed on the Android 15 / API 35 emulator, including all of the table above, address parsing on Android's own URL classes, five launches in a row showing different verses, the verse staying through a rotation and changing when AIR is reopened, and no YouTube row in search.
- Home across several launches, the search card with a query, a blocked entry, a failed page, and a loaded page's header with its lock were checked on the emulator.
- Every Korean verse was compared with a second digital copy (scrollmapper `KorRV`) and read through. The only differences were psalm titles, which are left out. Both copies share the typing error in 빌립보서 4:19, so they are probably not independent; that verse was confirmed against YouVersion's 개역한글. A shared error elsewhere would rest on the read-through alone.

Physical devices, Android 10–14, real payment apps, and real KakaoTalk or banking links were not tested. The engine stays GeckoView.

## Updates go straight to the newest release — 28 October 2026

Tapping the update button installed the release remembered from the last check, so a newer release published since then appeared only after that install and the next daily check. The button now fetches the latest manifest first, and installing the remembered release makes the next check due at once. The search card no longer moves while the keyboard opens. The four Dependabot action updates ship in the same release: `setup-java` 6.1.0, `setup-android` 4.0.4, `upload-artifact` 7.0.2, and `download-artifact` 8.0.2, each pinned SHA confirmed against its official tag.

- **58 navigation/Firebase tests** and **14 update tests** passed. Debug and optimized release builds passed; both Lint variants reported zero errors.
- **19 Android 15 / API 35 emulator integration tests** passed, including a check that installing a remembered release clears the 24-hour wait. The home search test passed three times in a row after the card was fixed in place; before, it once opened YouTube when Google was tapped.
- On the emulator, with a stale remembered release (`0.1.3-stale`) on the button, tapping it offered the newest public release, **0.1.3+1049**, instead.
- The new artifact actions run only on main, so the release run after merging is their first real use.
- Release version names are now plain `0.1.N`: a build with `AIR_VERSION_CODE=1052` reported `versionName='0.1.52'` in `aapt dump badging`, a local build kept `0.1.3`, and `AIR_VERSION_CODE=999` was rejected. Release-publication tests (10) passed.

## Spotlight search, quieter screens, and no built-in QR scanner — 28 October 2026

Home search is now a spotlight-style overlay: a floating search field near the top over a dimmed home screen (blurred on Android 12 and later), with Google and YouTube rows that appear once a query is typed. The home screen keeps only the logo, illustration, search bar, a default-browser hint when AIR is not the default, and the update button; slogan and guide copy was removed there, in search, and in block messages. Dialogs use rounded cards and bolder titles, the browser header is a back button, a site pill, and a **끝내기** pill, and Korean text breaks between phrases on Android 13 and later. The built-in QR scanner and the ZXing dependency were removed; the phone's camera app opens QR links in AIR when AIR is the default browser. The camera permission remains for websites.

- After merging the adult-site rules, **58 navigation/Firebase tests** and **14 update tests** passed. Debug and optimized release builds passed; both Lint variants reported zero errors.
- **19 Android 15 / API 35 emulator integration tests** passed (12 browser, seven updater), including home search through the new overlay and the nimi.wiki entry.
- Screens were checked on the emulator: home, the search overlay empty and with a query, a blocked entry, a loaded page header, and the first-run dialog. Physical devices, dark mode, and Android 10–11 (no blur) were not checked.

## Adult sites the family resolver misses — 28 October 2026

nimi.wiki, the X video ranking known as 트위터닷넷 or 니미위키, resolved normally through `family.cloudflare-dns.com`, while pornhub.com, twidouga.net, twivideo.net, and twiigle.com answered `0.0.0.0`. The literal twitter.net domain also passed the resolver; it currently redirects to a domain-for-sale page. AIR now refuses these on every route itself, with a separate adult-site message.

- **58 navigation/Firebase tests** (two new adult-site cases: listed domains, name rules, adult TLDs, Google wrappers, Google sign-in returns, and X, twitter.com, sussex.ac.uk, and namu.wiki staying open) and **14 update tests** passed. Debug and optimized release builds passed; both Lint variants reported zero errors.
- **12 Android 15 / API 35 emulator browser integration tests** passed, including a nimi.wiki entry that shows the adult-site message without starting the engine.

## SafeSearch text search, community blocking and filtered DNS — 28 October 2026

The home screen's search opens Google results with `safe=active&udm=14`; other Google search links are rewritten to that form, and other engines stay blocked. A fixed community list is refused on every route, and the engine resolves names only through Cloudflare's family DNS-over-HTTPS resolver.

- **52 navigation/Firebase tests** (seven new search and community cases), **14 update tests**, and **10 release-publication tests** passed. Debug and optimized unsigned release builds passed; both Lint variants reported zero errors.
- **48 Android 15 / API 35 emulator integration tests** passed (41 browser, seven updater), including a community entry, a non-Google search entry, and the home search opening the SafeSearch text URL. Local fixtures still load with DNS-over-HTTPS-only mode.
- With network access on the emulator, a home search loaded Google's text results through the family resolver, tapping the image tab reloaded text results, and an adult domain showed AIR's connection error. The resolver answered `0.0.0.0` for that domain and mapped `www.google.com` to `forcesafesearch.google.com`.

Before the YouTube rules below, a single video link reached YouTube's home feed and Shorts: YouTube changes its address in place, which never produced a load request. AIR now checks every address change and returns to the previous page when the new address is blocked. YouTube search opens from the home dialog; the home feed, subscriptions, and Shorts stay blocked; moving straight from one video to another is refused unless both are in the same chosen playlist; and a CSS-only built-in extension hides recommended videos, Shorts in results, and the Home/Shorts/You bar.

- **56 navigation/Firebase tests** (eleven search, community, and YouTube cases) and the release, update, and Lint checks above passed again. **48 emulator integration tests** passed.
- On the emulator, a YouTube video showed no recommended videos below the comments. Tapping the YouTube logo showed AIR's search toast and returned to the video. YouTube search from AIR showed regular results without Shorts or the bottom bar, and a result opened its video. Google results kept `safe=active&udm=14` when Google added its own parameter in place.

Physical devices, networks that block DNS-over-HTTPS, communities outside the list, YouTube autoplay into another video, and YouTube's desktop layout were not tested.

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
