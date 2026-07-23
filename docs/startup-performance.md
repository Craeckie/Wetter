# Startup performance: comparison with the lightningmaps sibling

This app and [`../lightningmaps`](../../lightningmaps) are both single-`WebView` wrappers built
on the workspace's `android-basic` template, so most of lightningmaps' startup work transfers
structurally. This doc records which of its levers already landed here, which don't apply, which
are worth doing (with effort/value), and the plans to do them. It is an **analysis + backlog**,
not a changelog — nothing below is implemented yet.

lightningmaps' own writeup is [`../lightningmaps/docs/startup-performance.md`](../../lightningmaps/docs/startup-performance.md);
its measured results are the reference numbers quoted here.

## The core similarity, and the one difference that matters

Both apps' "usable" moment is reached by: process start → WebView engine init + first paint →
page load. The difference is the final gate:

- **lightningmaps**: a streaming realtime **WebSocket** delivering the first lightning strike.
- **wetter**: a **static weather page** finishing its load.

That single difference cleanly rules a chunk of lightningmaps' work out (everything WebSocket-
or map-tile-specific) and, conversely, means wetter's gate is dominated by page-load and by the
app's own main-thread work before/around it.

**Headline finding:** wetter is arguably in a *worse* pre-optimization position than lightningmaps
was, because in night mode it does exactly what lightningmaps deleted — it **reads and parses the
340 KB `darkreader.js` bundle on the main thread inside the `AndroidView` factory**
(`app/src/main/java/com/example/wetter/MainActivity.kt:688` for the weather screen, `:885` for the
search screen). That is the standout lever here.

## Already present in wetter

Ported earlier (commits `1c68a25` "Backport WebView hardening" and `330ef94` "Speed up cold
launch"), or added independently:

| Lever | Where |
|---|---|
| Network-layer ad/CMP request blocking | `isBlockedAdHost` + `shouldInterceptRequest` |
| SourcePoint first-party-cloak host block | `SOURCEPOINT_FIRST_PARTY_HOST` regex (`^data-c\d+\.kachelmannwetter\.com$`) |
| Splash screen, first-paint handoff | `Theme.App.Starting`; `contentReady` flipped from `onPageCommitVisible` |
| Explicit `MATCH_PARENT` layout params | both WebViews |
| In-app error page with auto-retry | `errorPageHtml` |
| Console → logcat forwarding | `WebChromeClient.onConsoleMessage` (returns `false`) |
| `scripts/analyze_log.py` | present — but **no `--startup` timeline mode** (258 lines, single positional arg) |

## Not applicable (and why)

| lightningmaps lever | Why it doesn't apply to wetter |
|---|---|
| WebSocket warm-up / watchdog / host-rewrite | wetter has no realtime socket; its gate is an HTTP page load |
| CARTO tile retargeting, retina `{r}`, invert-tiles dark map | no self-managed map tile layer (wetter's radar is a page element) |
| `RELAYOUT_JS` / Leaflet `invalidateSize` | no Leaflet map to re-measure |

## Applicable levers, ranked for wetter

| ID | Lever | Effort | Value | Notes |
|---|---|---|---|---|
| **F** | Remove Dark Reader → rely on the site's own dark theme | Med (validation-heavy) | **Highest** | Deletes 340 KB from the APK **and** the main-thread read/parse; wetter already *prefers* the site's native theme |
| **G** | Drop Compose; build the WebView in `onCreate` | High | High | lightningmaps measured −53% `am start` TotalTime (debug upper bound; less on release). wetter's two-screen state machine makes it more work than lightningmaps' single screen |
| **D** | Baseline Profile (hand-written) | Low–Med | Modest, ships to real users | Self-contained `:baselineprofile` module add |
| **A** | `WebViewCompat.addDocumentStartJavaScript` | Low–Med | Modest | Earlier hide/dark-seed injection = less flash; consolidates the `onPageStarted`+`onPageFinished` double-inject. Needs `androidx.webkit` |
| **E** | DNS + preconnect warm-up | Low | Small | wetter knows the city host at process start (stored URL) |
| **B** | `BundleCache` disk cache | Med | **Unknown — gated on investigation** | Only worth it if kachelmann serves version-stamped immutable bundles (see below) |
| **C₂** | `uiMode` in `configChanges` + rebuild | Low–Med | Small | day/night flip is rare; today it triggers a full activity recreate |

## Current-state facts verified (2026-07-23)

- **Compose**: `MainActivity` uses `setContent { AppTheme { Scaffold { … } } }` with an
  `AndroidView`, and a two-screen state machine (`CitySearchWebView` ⇄ `WeatherWebView`, keyed on
  a remembered `cityUrl`). The two WebView setups are ~95% duplicated (~350 lines).
- **Dark Reader**: `app/src/main/assets/darkreader.js` is 346,017 bytes. Read on the main thread
  in night mode; enabled at `onPageStarted`, then disabled by `ENABLE_DARKREADER_JS`'s observer
  once the site's own `body.dark` appears (`AUTO_SITE_DARK_JS` clicks the site's dark toggle,
  persisted server-side). On launch #2+ the cookie makes the server render dark from the first
  byte, so Dark Reader **enables then immediately disables itself — pure overhead**.
- **`WetterApplication.onCreate`** is empty (no DNS warm-up).
- **Manifest** `configChanges` = `orientation|screenSize|keyboardHidden` — **no `uiMode`**, so a
  system day/night change fully recreates the activity.
- No `androidx.webkit` dependency; no `:baselineprofile` module.

## Open questions — pending an on-device capture

These block a firm decision on two items. See "What to capture" below.

1. **Size of the Dark Reader main-thread cost (Plan F/B).** Expected to show as a day-vs-night
   cold-launch delta. Not yet measured.
2. **BundleCache feasibility (Plan D).** Needs the site's JS/CSS request URLs + cache headers.
   The saved reference page (`scripts/reference-local/…`) has all assets inlined, so it can't
   answer this — only a live capture can. Build Plan D **only if** kachelmann serves
   version-stamped immutable bundles (like lightningmaps' `/min/?f=…&<stamp>`); if it's plain
   filenames on normal HTTP caching, Chromium already handles it and Plan D is dropped.
3. **Startup baseline** to order F vs G. Not yet measured.

---

## The plans

### Plan 0 — Measurement first
Make every change below measurable before optimizing.
- **Done:** `scripts/capture-startup.sh` (cold-launch capture + `--compare-theme` delta), and
  debug-only `StartupTrace` markers in the app (see "Instrumentation" below).
- **TODO:** port lightningmaps' `analyze_log.py --startup` timeline mode so the raw logcat is
  summarized automatically. Until then, `grep '\[startup' <log>` gives the timeline by hand.

### Plan A — Low-risk batch (each independently shippable)
1. **DNS/preconnect warm-up** — fill in `WetterApplication.onCreate` with a daemon-thread
   `InetAddress.getAllByName` for `kachelmannwetter.com` + the image CDN host(s); inject
   `<link rel=preconnect/dns-prefetch>` hints.
2. **Baseline Profile** — add a `:baselineprofile` module + `androidx.profileinstaller` + a
   hand-written `baseline-prof.txt` (wildcards for `com.example.wetter.**`, WebView, Compose).
   Straight port from lightningmaps (which had to hand-write it because its dev device can't
   capture one; the rules are device-independent class/method names).
3. **`addDocumentStartJavaScript`** for `INJECT_HIDE_STYLE_JS` (+ a pre-dark seed) — add
   `androidx.webkit`, feature-gate via `WebViewFeature.isFeatureSupported(DOCUMENT_START_SCRIPT)`,
   keep `onPageStarted`/`onPageFinished` as fallback. **Guard it to run only on the real
   kachelmann document** — lightningmaps learned the hard way that this callback also fires on
   `about:blank` and same-origin iframes (its "triple arming" regression).

### Plan B — Remove Dark Reader (the big win, own plan)
The nuance that makes this *cleaner* for wetter than it was for lightningmaps: wetter **already
prefers the site's own dark theme** — `AUTO_SITE_DARK_JS` clicks the site toggle (persisted via
cookie), and the site renders Highcharts correctly, which Dark Reader cannot. Dark Reader is only
a bridge until that activates, and on launch #2+ it's pure overhead (above).

Steps:
- **Validate on device**: confirm launch #2+ renders `body.dark` server-side from the first byte,
  and that the first-ever-launch flash is acceptable with only a CSS seed.
- **Replace** the 340 KB engine with a small hand-written **pre-dark CSS seed** (dark `html`/`body`,
  reusing the existing `AMOLED_CSS`) injected at document-start to bridge the flash. The site's
  native theme *is* wetter's "hand-written dark stylesheet" — better than one we'd author, which
  is why this is cleaner than lightningmaps' hand-written-CSS replacement.
- **Delete** `darkreader.js`, its LICENSE, and all injection plumbing
  (`injectDarkReaderJs`, `ENABLE_DARKREADER_JS`, the asset read). The main-thread read vanishes
  with it. Keep `AUTO_SITE_DARK_JS`.
- **Risk**: if the site's toggle/cookie ever fails, content is light-on-black for that load
  (background stays dark via the seed). Acceptable given how much the app already leans on the
  native theme.

### Plan C — Drop Compose (biggest structural change, own plan)
lightningmaps builds the WebView directly in `onCreate`; wetter wraps it in `Scaffold` +
`AndroidView` plus the two-screen state machine. To remove Compose:
- `onCreate` reads `cityUrl`: null → build the search WebView with a selection listener that saves
  the URL and swaps `setContentView` to the weather WebView; non-null → build the weather WebView
  directly.
- **Companion refactor (do first, independently valuable):** unify the ~95%-duplicated
  `CitySearchWebView` / `WeatherWebView` into one `buildWebView(url, isSearch)`. It de-risks the
  Compose removal and shrinks the maintenance surface on its own.
- Drops `activity-compose`, `compose-bom`, `material3`, `foundation`, `ui*` → sizeable APK +
  startup win. Highest payoff, highest risk.
- **Synergy**: doing Plan B first means the unified builder carries no Dark-Reader branch across.

### Plan D — BundleCache (gated on investigation)
Do not build until the capture (below) shows kachelmann serves version-stamped, immutable bundles.
If so, port `BundleCache.kt` with an `isCacheableBundle` matcher for kachelmann's URL shape and an
allowlist for the site + image CDNs. If not, drop it.

### Plan E — `uiMode` in `configChanges`
Fold into Plan C: once `onCreate` owns the WebView, add `uiMode` to `android:configChanges` and
rebuild the WebView in `onConfigurationChanged` on an actual night-bit change, skipping the full
activity teardown. Small, rare-path win; cheap once Compose is gone.

## Recommended sequencing

**0 → A → B → C**, with D spun off only if the capture justifies it, and E folded into C. **Plan B
is the single highest value-to-effort item** and is where to start after wiring up measurement.
Semver: these are optimizations, so patch bumps (e.g. 1.2.1 → 1.2.2) unless a plan adds a
user-visible feature.

## Instrumentation (debug builds only)

`StartupTrace` (`app/src/main/java/com/example/wetter/StartupTrace.kt`) logs cold-start markers
under the `Wetter` logcat tag, each prefixed `[startup +<ms>]` where `<ms>` is measured from the
process fork. Gated on the debuggable flag, so **release builds log nothing**. Markers, in order:

- `WetterApplication.onCreate` — earliest app-code point
- `MainActivity.onCreate` / `MainActivity.onCreate returned`
- **`read darkreader.js: N chars in Mms on main thread`** — the headline; `M` is the night-mode-
  only cost Plan B removes (emitted only in dark mode)
- `onPageCommitVisible (first paint)`
- `onPageFinished (weather|search)`

Isolate the timeline with `adb logcat -s Wetter | grep '\[startup'`.

## What to capture (to close the open questions)

All on a **debug build**, freshly cold-started. The capture script handles the force-stop,
keep-awake, theme flip, and buffer dump.

1. **Sizes Plan B — cheapest, sharpest.** Day-vs-night cold-launch delta ≈ the Dark Reader
   main-thread cost:
   ```
   scripts/capture-startup.sh --compare-theme
   ```
   Runs N cold launches in light then dark and prints the median `TotalTime` delta. Cross-check
   against the `read darkreader.js … Mms` marker in the dumped `startup.log`.
2. **Unblocks Plan D.** `chrome://inspect#devices` (debug builds enable it) → open page →
   **Network** tab → JS/CSS rows: look for version-stamped/hashed URLs with long-lived
   `cache-control`. Cheaper partial alternative: `adb logcat -s Wetter` — console forwarding tags
   each message with its `(sourceId:line)`, leaking many script URLs.
3. **Startup baseline (orders F vs G).**
   ```
   scripts/capture-startup.sh -n 6
   ```
   Writes `startup.log`; read the `[startup +Nms]` timeline plus `Displayed …MainActivity`,
   WebView provider load, `Choreographer`/`Davey!` jank, and GC pauses from the same file.
