# Third-party components

AIR uses upstream libraries without modifying their source. The original licenses and notices packaged by their publishers remain in the dependencies.

| Component | License | Original project |
|---|---|---|
| GeckoView 158.0.20261007201254 | Mozilla Public License 2.0 and bundled component licenses | https://mozilla.github.io/geckoview/ |
| AndroidX (AppCompat, Browser, Core and transitive libraries) | Apache License 2.0 | https://android.googlesource.com/platform/frameworks/support/ |
| Google Play Services FIDO (via GeckoView) | Publisher SDK terms | https://developers.google.com/android/guides/overview |
| OpenJDK desugared libraries | GPLv2 with Classpath Exception and Apache License 2.0 notices in the artifact | https://github.com/google/desugar_jdk_libs |

## Bible text

The home screen's verses (`app/src/main/assets/verses.tsv`) come from two texts:

| Text | Status | Source |
|---|---|---|
| Korean Revised Version (개역한글, 1952/1961) | Published as public domain by the source; its copyright is widely reported to have expired in 2012 | https://api.getbible.net/v2/korean.json |
| World English Bible, American edition (`engwebp`) | Public domain; "World English Bible" is a trademark of eBible.org | https://ebible.org/find/details.php?id=engwebp |

Psalm titles are left out. In the English, quotation marks that open or close outside the verse are removed, and the first letter and last punctuation mark are adjusted so a verse reads alone. In the Korean, one typing error in the source (빌립보서 4:19, "가운게") is corrected to "가운데".

## Engine artifacts

GeckoView binary and source artifacts:

- https://maven.mozilla.org/maven2/org/mozilla/geckoview/geckoview/158.0.20261007201254/
- Source revision published by Mozilla: `eb9a6e3a736802c8e080a76c44dc81f409d99d54`
- https://hg.mozilla.org/releases/mozilla-release/rev/eb9a6e3a736802c8e080a76c44dc81f409d99d54
- MPL 2.0: https://www.mozilla.org/en-US/MPL/2.0/
- Apache License 2.0: https://www.apache.org/licenses/LICENSE-2.0

Review the engine's bundled licenses and distribution requirements before shipping a store release. AIR's locally generated debug signing key is for testing only.
