# android-basic template

**Type: native Android app (installable APK).** Runs entirely on-device — no server needed.
**Offline by default** — the manifest declares no permissions, including no `INTERNET`.
See [README](../README.md) for guidance on when to add networking.

Minimal Jetpack Compose Android app — single module, no DI framework, Material 3.
Modeled on [parcels](https://github.com/itsvic-dev/parcel-android).

## Placeholder values

| Placeholder | Default | Meaning |
|---|---|---|
| `com.example.wetter` | — | Application & package ID (`namespace`, `applicationId`, package declarations) |
| `Wetter` | — | Human-readable app name in `strings.xml` |
| `Wetter` | — | `rootProject.name` in `settings.gradle.kts` |

Use `new-project.sh android-basic` to replace these automatically, or do it manually.

## Key versions

- AGP `8.9.1` / Kotlin `2.1.0` / Compose BOM `2025.03.01`
- compileSdk `35` / minSdk `26` / targetSdk `35` / jvmTarget `11`
- Gradle `8.11.1`

## Structure

```
app/src/main/java/com.example.wetter/
  MainActivity.kt          # ComponentActivity + setContent { AppTheme { ... } }
  Wetterlication.kt   # Application subclass — wire singletons here
  ui/theme/
    Color.kt               # Brand palette (replace with Material Theme Builder output)
    Type.kt                # Typography scale
    Theme.kt               # AppTheme composable — supports dynamic color (Android 12+)
```

## First steps after scaffolding

1. Replace the launcher icon drawables in `app/src/main/res/drawable/` with your own.
2. Update `app_name` in `res/values/strings.xml`.
3. Change `minSdk` in `app/build.gradle.kts` to match your target.
4. Add dependencies to `gradle/libs.versions.toml` and `app/build.gradle.kts`.

## Extending

- **Navigation:** add `navigation-compose` + `navigation` version to `libs.versions.toml`, then add `NavHost` in `MainActivity.kt`.
- **Persistence:** add Room (`room-runtime`, `room-ktx`, `room-compiler` via KSP) + `ksp` plugin.
- **Networking:** add OkHttp + Retrofit or Ktor.
- **DI:** if the app grows large, migrate to the `android-compose` template which includes Hilt.

## Debugging the WebView (scroll/consent issues)

`MainActivity.kt` embeds the target site in a `WebView` and injects a couple of JS/CSS
workarounds on every page load:

- `INJECT_HIDE_STYLE_JS` — hides a few page elements (cosmetic, carried over from uBlock filters).
- `UNLOCK_SCROLL_JS` — counters a scroll-lock bug: the site's consent-management script
  (SourcePoint) sets `body { position: fixed; overflow: hidden }` inline via
  `style.setProperty(..., 'important')` while showing its consent dialog, then never releases
  it in this WebView (the dialog doesn't render), leaving the page permanently frozen. A
  stylesheet `!important` rule can't win that fight — inline `!important` always beats
  stylesheet `!important` for the same element — so instead a `MutationObserver` watches
  `body`'s inline style and re-asserts our own inline `!important` override every time the
  site (re-)locks it.

If scrolling (or similar page-behavior) breaks again, capture a log and run it through the
analyzer:

1. On the device: Settings → enable Developer options → **Bug report** (or `adb bugreport`),
   or just `adb logcat -v threadtime > log.txt` while reproducing the issue. The in-app
   "share log" flow (dev options → take bug report → share) works too.
2. Analyze it:
   ```bash
   python3 scripts/analyze_log.py path/to/log.txt
   ```
   It reports: which WebView engine rendered the page, any consent-management (CMP) script
   activity, a scroll-state timeline (only present if the app was a **debug** build — see
   below), chromium console warnings/errors, crashes/ANRs, and enabled accessibility services
   flagged if they could intercept touch/scroll.
3. Debug builds also install `SCROLL_WATCH_JS`, which logs a snapshot (`__wetter_scroll_watch__`
   in logcat) every time the page's scrollability or `body`'s position/overflow changes — this
   is what the analyzer's "Scroll state timeline" section reads. Release builds skip it.
4. Debug builds also call `WebView.setWebContentsDebuggingEnabled(true)`, so the live page can
   be inspected directly via `chrome://inspect#devices` on a computer with the phone connected
   over USB.

## Local release (optional)

This template ships with CI signing but **no local release script** (the `android-compose`
template includes one ready-made). Follow the workspace-wide signing convention — central
keystore at `../my-debug.jks`, script at `scripts/release.sh`, signed APK written to the project
root as `my-app-signed.apk` — documented in the [templates README](../README.md#local-release--signing-android).
To build and sign an APK locally — handy for sideloading or F-Droid-style distribution — add
`scripts/release.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew --stop
./gradlew assembleDebug --no-daemon
apksigner sign --ks ../my-debug.jks --ks-key-alias my-key --ks-pass "pass:$1" \
  --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled false \
  --out "$(pwd)/my-app-signed.apk" app/build/outputs/apk/debug/app-debug.apk
```

Then `chmod +x scripts/release.sh`. It stops the Gradle daemon, builds the debug APK, and signs
it with `apksigner`, writing `my-app-signed.apk` to the project root. Run it with
`./scripts/release.sh <keystore-password>`.

Prerequisites:
1. **`apksigner` on PATH** (ships with SDK build-tools):
   ```bash
   export PATH="$PATH:$ANDROID_HOME/build-tools/$(ls $ANDROID_HOME/build-tools | tail -1)"
   ```
2. **Keystore at `../my-debug.jks`** (one level above the project root, shared across workspace
   projects). Create once if it doesn't exist:
   ```bash
   keytool -genkey -v -keystore ../my-debug.jks \
     -alias my-key -keyalg RSA -keysize 2048 -validity 10000 \
     -storepass <password> -keypass <password> -dname "CN=Dev, O=Dev, C=US"
   ```

## CI

`.github/workflows/build.yml` runs unit tests and builds a release APK on every push.
APK signing is optional — add `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`
as GitHub repo secrets to enable it.
