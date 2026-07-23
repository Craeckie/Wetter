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

**Headline finding (measured 2026-07-23, see below):** the two addressable windows in a ~2 s cold
start are **~700 ms of Compose composition + WebView construction** (`onCreate` → first frame) and
**~950 ms of page load** (first frame → first paint). The 340 KB `darkreader.js` read that looked
like the obvious lever is a **non-issue: 6–8 ms on the main thread**, because the read is cheap I/O
and the expensive parse runs asynchronously in the WebView renderer, off the `am start` critical
path. Dark mode measured no slower than light. So the top cold-start lever is **dropping Compose
(Plan C)**, not removing Dark Reader (Plan B) — the opposite of this doc's first draft. Measurement
earned its keep.

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

## Applicable levers, ranked for wetter (measurement-informed)

Reordered after the 2026-07-23 capture. The "Plan" column maps to the plans below.

| Lever | Plan | Effort | Value | Notes |
|---|---|---|---|---|
| Drop Compose; build the WebView in `onCreate` | **C** | High | **Highest for cold start** | Targets the ~700 ms `onCreate`→first-frame window (composition + WebView construction). Removing Compose lets `onCreate` `loadUrl` immediately instead of waiting on composition. lightningmaps saw −53% `am start` (debug upper bound; less on release) |
| DNS + preconnect warm-up | A.1 | Low | Small–Med | Trims part of the ~950 ms page-load window; the city host is known at process start (stored URL) |
| Baseline Profile (hand-written) | A.2 | Low–Med | Modest, ships to real users | Self-contained `:baselineprofile` module add |
| `WebViewCompat.addDocumentStartJavaScript` | A.3 | Low–Med | Modest | Earlier hide/dark-seed injection = less flash; consolidates the `onPageStarted`+`onPageFinished` double-inject. Needs `androidx.webkit` |
| ~~`BundleCache` disk cache~~ | ~~D~~ | — | **Dropped (measured).** Chromium already caches every bundle with no revalidation; see Open questions | — |
| Remove Dark Reader → rely on site's own dark theme | **B** | Med | **Not a cold-start lever (measured 6–8 ms).** APK −340 KB, memory, renderer CPU, and pure waste on launch #2+ | Demoted from the first draft's "highest"; still worth doing as cleanup |
| `uiMode` in `configChanges` + rebuild | E | Low–Med | Small | day/night flip is rare; today it triggers a full activity recreate |

## Measured baseline (2026-07-23, on device, debug build)

`scripts/capture-startup.sh --compare-theme` + a no-arg run. Medians over steady-state cold
launches (excluding the post-flip transition launch, which logged an anomalous +10 s
`onCreate` — a `cmd uimode` background-restart artifact, not a user path). All deltas from
process fork.

| Milestone | Light (n=5) | Dark (n=5) |
|---|---|---|
| `MainActivity.onCreate` | 356 ms | 343 ms |
| **`read darkreader.js`** | — | **6–7 ms** |
| `onCreate returned` (1st frame) | 1065 ms | 1078 ms |
| `onPageCommitVisible` (first paint) | 2038 ms | 1785 ms |
| `Displayed` (am metric) | 2204 ms | 1925 ms |

Where the ~2 s goes:

```
0 ──►~350ms    process fork → onCreate         cold process + Application (not app-addressable)
~350 ──►~1065  onCreate → first frame          ~700ms  Compose composition + WebView construction
~1065 ──►~2000 first frame → page first paint  ~950ms  page load (network + render)
```

Conclusions:
- **Dark Reader is not a cold-start cost.** The read is 6–8 ms; dark launches were if anything
  *faster* than light. Its parse is async in the renderer, off the `am start` path. Plan B is
  reclassified as APK/memory/CPU cleanup.
- **The ~700 ms `onCreate`→first-frame window is the top app-side lever** → Plan C (drop Compose).
- **The ~950 ms page-load window** is network-bound → Plan A.1 (warm-up) + Plan D (if applicable).

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

## Open questions

1. **Size of the Dark Reader main-thread cost.** ✅ **Answered 2026-07-23: 6–8 ms, negligible.**
   See Measured baseline above.
2. **BundleCache feasibility (Plan D).** ✅ **Answered 2026-07-23: dropped.** The
   `RESOURCE_TIMING_DUMP_JS` capture showed **every same-origin bundle at `transfer: 0` on cold
   launch #2** — Chromium serves them all from its own HTTP cache with *no* revalidation
   round-trip. The site version-stamps its asset URLs (`?v3.42`, `?v4.2g`, Yii content-hash dirs
   like `/assets/c6acb98a/`), so the browser cache stays valid across builds. An app-level disk
   cache would only duplicate what Chromium already does. Plan D removed.
3. **Startup baseline to order the plans.** ✅ **Answered 2026-07-23** (Measured baseline above):
   Compose/WebView-init dominates the app-side window, so Plan C leads.

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

### Plan B — Remove Dark Reader (cleanup, not a startup lever)
**Measurement (above) demoted this from the first draft's headline: the main-thread read is
6–8 ms.** It's still worth doing — but for APK size (−340 KB), memory, renderer CPU during load,
and eliminating pure-waste work on launch #2+ — not for cold-start time. The nuance that makes it
*clean* for wetter: it **already prefers the site's own dark theme** — `AUTO_SITE_DARK_JS` clicks
the site toggle (persisted via cookie), and the site renders Highcharts correctly, which Dark
Reader cannot. Dark Reader is only a bridge until that activates, and on launch #2+ it's overhead.

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

### Plan C — Drop Compose (top cold-start lever, own plan)
This is where the measured ~700 ms `onCreate`→first-frame window lives (composition + WebView
construction). lightningmaps builds the WebView directly in `onCreate`; wetter wraps it in `Scaffold` +
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

### ~~Plan D — BundleCache~~ (dropped, measured 2026-07-23)
Investigated and dropped: Chromium already caches all of the site's version-stamped bundles from
disk with no revalidation (`transfer: 0` on cold launch #2 — see Open questions #2). An app-level
disk cache would add nothing. Unlike lightningmaps' Minify combiner, kachelmann already ships
cache-friendly asset URLs. Re-check with `RESOURCE_TIMING_DUMP_JS` if the site's caching ever
regresses (bundles showing `transfer > 0` on repeat launches).

### Plan E — `uiMode` in `configChanges`
Fold into Plan C: once `onCreate` owns the WebView, add `uiMode` to `android:configChanges` and
rebuild the WebView in `onConfigurationChanged` on an actual night-bit change, skipping the full
activity teardown. Small, rare-path win; cheap once Compose is gone.

## Recommended sequencing

Revised after measurement: **0 → A → C → B**, with **D dropped** and E folded into C. **Plan C is
the top cold-start lever** — start there once the low-risk Plan A batch is in. Plan A.1 (warm-up)
is the cheapest trim of the page-load window. Plan B drops to cleanup and can land any time.
Semver: these are optimizations, so patch bumps (e.g. 1.2.1 → 1.2.2) unless a plan adds a
user-visible feature.

## Backlog (not yet a plan)

- **The page ships ~2.5 MB of decoded JS.** The `RESOURCE_TIMING_DUMP_JS` capture (2026-07-23)
  showed `graph.js?v2.82` at **1.5 MB decoded** (Highcharts), plus `function.js` ~500 KB and
  `compact.js` ~390 KB. Parsing/executing that is a real slice of the ~950 ms page-load window —
  and far larger than the Dark Reader cost that first looked like the lever. The charts it powers
  render *below the fold* (the reorder puts the 12-hour overview first, radar, then future-day
  charts). Deferring or lazy-loading the heavy chart script until after first paint could cut
  time-to-first-paint, but it's a site-behavior change we'd have to force via injection (risky,
  and it fights the site's own script ordering) — needs experimentation before it's a plan.
  This is likely the biggest remaining page-load lever after Compose removal.

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
