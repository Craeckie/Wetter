package com.example.wetter

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.viewinterop.AndroidView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.wetter.ui.theme.AppTheme

private const val WEATHER_URL = "https://kachelmannwetter.com/de/wetter/2892794-city"

// Elements to strip from the page, carried over from the equivalent uBlock Origin
// cosmetic filters (kachelmannwetter.com##...).
private val HIDE_SELECTORS = listOf(
    // Annoyances / overlays.
    "#countrydetection",
    "div.weather-infos",
    ".geonames-info",
    ".alert-dismissible.alert-default.alert",
    "div.md-mob-margin.mdcss-mobile",
    ".md-center.mdcss-mobile",
    "div.spacer-lh-1",
    ".modal-body > p",
    ".modal-body > .table:nth-of-type(1)",
    ".modal-body > .alert-warning.alert",
    // Ad slots. These reserve layout height even when no ad loads (none does in
    // this WebView), so leaving them in shows up as large empty bands -- most
    // visibly the top "billboard" margin. Selectors from the maintained public
    // lists: uBlock uAssets (dkpw-billboard-margin*, kw-ad-right, #meteosafe,
    // pflotsh promo) and EasyList Germany (.dkpw).
    // Footer content below the country selector: the advertising-contact /
    // B2B block next to it, and the whole bottom bar (social links, app/API
    // links, legal links, copyright).
    "footer.footer2 .footer-text",
    ".footer2-bottom",
    ".dkpw-billboard-margin",
    ".dkpw-billboard-margin-fixed",
    ".dkpw",
    ".kw-ad-right",
    "#meteosafe",
    "[href=\"https://pflotsh.com\"]",
    // SourcePoint consent/CMP overlay ("Willkommen auf unserem Wetterportal" welcome +
    // tracking-consent dialog). Its actual content renders inside SourcePoint's own
    // message iframe, so it can't be targeted by content -- these are the same
    // convention-based id/class patterns public cookie-notice filter lists (uBlock/
    // EasyList) use for SourcePoint's standard container markup, not something scraped
    // from this specific page. Hiding it here is purely cosmetic; UNLOCK_SCROLL_JS
    // already handles the body scroll-lock this same script applies underneath it.
    "iframe[id^=\"sp_message_iframe_\"]",
    "div[id^=\"sp_message_container_\"]",
    ".message-container",
)

// The weather-infos divs are mostly SEO text, hidden above -- except two of them, which
// hold the model-picker buttons (a.make-btn-mobile) for the compact forecast and the
// 14-day trend. Those divs are re-shown, but with their text collapsed (font-size: 0 +
// visibility: hidden) so only the buttons render. :not(:has(h2,h3,h4)) keeps the third
// button-holding div -- the ~50 "nearby places" links under a heading -- hidden.
//
// The buttons themselves are restyled as compact chips, with the model's parameters
// encoded visually (classes added by CLASSIFY_MODEL_BUTTONS_JS below):
//   - update interval: a leading lightning bolt marks Rapid-Update/Nowcast models
//   - resolution: a light blue border marks HD models
//   - scope: global models (rarely used) are demoted -- moved to the end of the list
//     and drawn as small gray outline chips; regional/local ones keep the solid blue.
private val SHOW_BUTTONS_CSS = """
    div.weather-infos:has(a.make-btn-mobile):not(:has(h2, h3, h4)) {
        display: block !important;
        visibility: hidden !important;
        font-size: 0 !important;
        line-height: 0 !important;
    }
    div.weather-infos a.make-btn-mobile {
        visibility: visible !important;
        display: inline-block !important;
        font-size: 12px !important;
        line-height: 1.3 !important;
        font-weight: normal !important;
        padding: 4px 10px !important;
        /* Also restores the inter-button gap that was a whitespace text node
           before font-size: 0 collapsed it. */
        margin: 0 4px 5px 0 !important;
        border-radius: 12px !important;
        border: 1px solid transparent !important;
    }
    div.weather-infos a.make-btn-mobile.w-rapid::before { content: '\26A1 '; }
    div.weather-infos a.make-btn-mobile.w-hd { border-color: #7fb3e8 !important; }
    div.weather-infos a.make-btn-mobile.w-global {
        background: transparent !important;
        border-color: #555 !important;
        color: #999 !important;
        font-size: 11px !important;
        padding: 2px 8px !important;
    }
    div.weather-infos a.make-btn-mobile.w-global.w-hd { border-color: #47617e !important; }
"""

// Turns the horizontally-scrolling hourly tile carousel into a compact vertical list of
// rows (one per hour), matching the app's overall vertical-scroll layout instead of adding
// a second, orthogonal scroll axis.
//
// Native structure (from scripts/reference-local/kachelmannwetter-reference-page.html):
//   .nexthours-scroll (overflow-x:scroll, height:140px)
//     > .nexthours-wrapper (width:3043px -- 24 tiles x 126.7px, floated in one row)
//         .nexthours-hour (width:126.7px, float:left) x 24
//           .fc-hours (time, colored badge) / .fc-symbol (icon) / .fc-temp / .fc-rain (%)
// Every dimension above exists purely to force 24 fixed-width cards onto one floated row
// inside a horizontally-scrollable viewport; overriding scroll/width/float turns the same
// markup into an ordinary flex column, and turning each tile into a flex row lays its four
// children (time/icon/temp/rain) out side by side as one compact list row.
private val COMPACT_HOURLY_CSS = """
    .nexthours-scroll {
        height: auto !important;
        overflow-x: visible !important;
        overflow-y: visible !important;
    }
    .nexthours-wrapper {
        width: 100% !important;
        display: flex !important;
        flex-direction: column !important;
    }
    .nexthours-hour {
        width: 100% !important;
        float: none !important;
        display: flex !important;
        flex-direction: row !important;
        align-items: center !important;
        padding: 4px 8px !important;
        border-left: none !important;
        border-bottom: 1px solid var(--gray-300);
    }
    .nexthours-hour:last-child { border-bottom: none; }
    .nexthours-hour .fc-hours {
        flex: 0 0 60px !important;
        border-radius: 6px !important;
        padding: 3px 0 !important;
        margin: 0 8px 0 0 !important;
    }
    .nexthours-hour .fc-symbol {
        flex: 0 0 36px !important;
        margin: 0 8px !important;
    }
    .nexthours-hour .fc-temp {
        flex: 0 0 56px !important;
        text-align: right !important;
    }
    .nexthours-hour .fc-rain {
        flex: 1 1 auto !important;
        text-align: right !important;
    }
    /* The page renders 24 hourly tiles; keep only the next 12. kacheln-first (the
       current hour) is the 1st tile, so the 13th-and-on are the ones beyond 12h out. */
    .nexthours-hour:nth-child(n + 13) { display: none !important; }
"""

// Tags each model-picker button with classes for SHOW_BUTTONS_CSS (scope/interval/
// resolution parsed from the button label) and moves global-model buttons behind the
// regional ones. Classification keywords: model names containing "Global"/"ECMWF"/other
// global-model acronyms are global unless the name also scopes them to Europe
// ("Mitteleuropa", "Europa ", Swiss); "UKMO(?! UK)" keeps "Großbritannien HD (UKMO UK)"
// -- the UK regional model -- out of the global bucket while "Global Britain (UKMO)"
// still lands in it. Idempotent: re-adding classes and re-appending in the same order
// is a no-op, so re-running it on the same document is safe.
private val CLASSIFY_MODEL_BUTTONS_JS = """
    (function() {
        document.querySelectorAll('div.weather-infos a.make-btn-mobile').forEach(function(a) {
            var t = a.textContent;
            var global = (/Global|ECMWF|GFS|JMA|GEM|UKMO(?! UK)|ACCESS|GDAPS|ARPEGE/i.test(t)
                && !/Mitteleuropa|Europa |Swiss|Schweiz/i.test(t)) || /\(Global\)/.test(t);
            a.classList.add(global ? 'w-global' : 'w-regional');
            if (/Rapid|Nowcast/i.test(t)) { a.classList.add('w-rapid'); }
            if (/HD/.test(t)) { a.classList.add('w-hd'); }
        });
        document.querySelectorAll('div.weather-infos').forEach(function(div) {
            if (div.querySelector('h2, h3, h4')) { return; }
            var globals = div.querySelectorAll('a.make-btn-mobile.w-global');
            if (!globals.length) { return; }
            var p = globals[0].parentElement;
            globals.forEach(function(b) { p.appendChild(b); });
        });
    })();
""".trimIndent()

// Reorders the three main sections of the forecast column into: 12-hour overview, radar
// map, future days. The page's own order is headline -> radar map (#weather-overview-maps)
// -> hourly tiles (#weather-overview-nexthoursdays) -> 2-day/14-day charts, so only the
// radar and hourly-tiles blocks need to swap; the charts already come last. All three are
// direct children of #kompakt-vorhersage (the col-md-8 forecast column), so a flexbox
// `order` override reorders them without moving any DOM nodes. IDs confirmed against
// scripts/reference-local/kachelmannwetter-reference-page.html.
private val REORDER_SECTIONS_CSS = """
    #kompakt-vorhersage { display: flex !important; flex-direction: column !important; }
    #kompakt-vorhersage > * { order: 3 !important; }
    #kompakt-vorhersage > #weather-overview-mesoanalyse,
    #kompakt-vorhersage > #forecast-url,
    #kompakt-vorhersage > #weather-overview-uwz { order: 0 !important; }
    #kompakt-vorhersage > #weather-overview-nexthoursdays { order: 1 !important; }
    #kompakt-vorhersage > #weather-overview-maps { order: 2 !important; }
"""

private val HIDE_CSS =
    HIDE_SELECTORS.joinToString(separator = ",\n") { it } + " { display: none !important; }\n" +
        SHOW_BUTTONS_CSS + "\n" + REORDER_SECTIONS_CSS + "\n" + COMPACT_HOURLY_CSS

// Injects a <style> tag with the hide rules, guarded by id so repeated calls
// (onPageStarted + onPageFinished) don't insert it twice. display:none !important
// keeps hiding elements that get (re)inserted dynamically after load, e.g. the
// country-detection popup and dismissible alerts.
private val INJECT_HIDE_STYLE_JS = """
    (function() {
        var id = '__wetter_hide_style';
        if (document.getElementById(id)) { return; }
        var style = document.createElement('style');
        style.id = id;
        style.textContent = ${jsStringLiteral(HIDE_CSS)};
        document.head.appendChild(style);
    })();
""".trimIndent()

// Dark theme via the real Dark Reader engine (bundled MIT-licensed library, see
// app/src/main/assets/darkreader.js + darkreader-LICENSE.txt), not a CSS filter hack.
// Dark Reader's Dynamic Theme analyzes each element's actual colors and computes a
// matching dark replacement per element, which is why saturated content (sun icons,
// the forecast chart line, section banners) comes out looking natural instead of
// hue-shifted the way a blanket filter:invert(...)+hue-rotate(180deg) does.
//
// Enabling it is idempotent (safe to call from both onPageStarted and onPageFinished),
// and the "if (!window.DarkReader)" guard around the bundle text keeps it from being
// re-parsed twice within the same document.
//
// Dark Reader is skipped entirely when the site's *own* dark theme is active (the
// server renders <body class="... dark"> then). Running it on top of an already-dark
// page is not just redundant, it wrecks the Highcharts diagrams: their grid lines are
// inline SVG strokes in near-black (#171717/#262627), which Dark Reader "fixes" by
// inverting to near-white, turning the subtle grid glaring bright. The body class can
// flip without a page load (the site's theme toggle), so a MutationObserver keeps
// Dark Reader's state in sync with it.
private val ENABLE_DARKREADER_JS = """
    (function () {
        if (!window.DarkReader) { return; }
        // The standalone bundle can't use the browser extension's privileged
        // cross-origin fetch for stylesheets; routing through the page's own fetch
        // at least covers same-origin sheets (this page's own CSS).
        DarkReader.setFetchMethod(window.fetch);
        function apply() {
            var siteDark = document.body && document.body.classList.contains('dark');
            if (siteDark) {
                if (DarkReader.isEnabled()) { DarkReader.disable(); }
            } else if (!DarkReader.isEnabled()) {
                DarkReader.enable({ brightness: 100, contrast: 100, sepia: 0 });
            }
        }
        if (document.body) {
            apply();
        } else {
            // onPageStarted can run before <body> exists; decide once it does.
            document.addEventListener('DOMContentLoaded', apply);
        }
        if (document.body && !window.__wetterDarkObserverInstalled) {
            window.__wetterDarkObserverInstalled = true;
            new MutationObserver(apply).observe(document.body, {
                attributes: true,
                attributeFilter: ['class'],
            });
        }
    })();
""".trimIndent()

// When the system is in night mode but the site is still on its light theme, click the
// site's own dark-mode toggle once. That flips the theme the site-native way (persisted
// server-side via cookie, so subsequent loads render dark directly), and the body-class
// observer in ENABLE_DARKREADER_JS then shuts Dark Reader off -- the native dark theme
// renders the Highcharts diagrams correctly, which Dark Reader does not (see above).
// Guards: body.dark check means an already-dark site is never toggled back to light, and
// the sessionStorage flag means we only ever auto-click once per WebView session, so a
// user tapping the toggle to deliberately go light isn't fought. The retry loop covers
// the toggle's click handler living in a late-loading script bundle.
private val AUTO_SITE_DARK_JS = """
    (function() {
        if (window.__wetterAutoDarkInstalled) { return; }
        window.__wetterAutoDarkInstalled = true;
        try { if (sessionStorage.getItem('__wetterAutoDark')) { return; } } catch (e) {}
        function attempt(tries) {
            var body = document.body;
            if (!body || body.classList.contains('dark')) { return; }
            var btn = document.querySelector('button.darkmode-toggle');
            if (btn) {
                try { sessionStorage.setItem('__wetterAutoDark', '1'); } catch (e) {}
                btn.click();
            } else if (tries > 0) {
                setTimeout(function() { attempt(tries - 1); }, 500);
            }
        }
        attempt(10);
    })();
""".trimIndent()

private fun injectDarkReaderJs(bundle: String): String = """
    (function() {
        if (!window.DarkReader) {
            $bundle
        }
    })();
""".trimIndent()

// The page's consent-management script (SourcePoint) locks background scroll by setting
// `body { position: fixed; overflow: hidden }` inline via style.setProperty(..., 'important')
// while its consent dialog is shown, then restores it once dismissed. In this WebView the
// lock gets applied but the matching dialog never renders, so the release never fires and
// the page is stuck unscrollable. A stylesheet rule can't out-rank that: an inline
// !important declaration always wins over a stylesheet !important declaration for the same
// element, regardless of selector. So instead we reassert our own inline !important override
// in JS -- inline vs. inline is decided by whoever set it last, so re-applying after the lock
// wins. Scoped to just these two properties on body itself, so legitimate fixed/sticky
// elements elsewhere on the page are unaffected.
//
// Reasserting is driven by two things:
//   1. A MutationObserver on body's inline style -- catches the lock whenever it lands.
//   2. A bounded requestAnimationFrame loop for the first few seconds after injection.
// The observer alone is racy: the lock arrives ~0.5s after onPageFinished, and depending on
// timing the observer would sometimes only react to a *later* unrelated body mutation (or not
// until the page was reloaded), leaving scroll frozen for seconds. The rAF loop closes that
// window -- worst-case latency becomes one frame -- and covers the case where the observer
// misses the exact lock mutation (e.g. body being replaced). It stops after UNLOCK_WINDOW_MS
// since the orphaned consent lock only ever lands right after load; the observer stays on for
// anything later.
private const val UNLOCK_WINDOW_MS = 8000
private val UNLOCK_SCROLL_JS = """
    (function() {
        if (window.__wetterUnlockScrollInstalled) { return; }
        window.__wetterUnlockScrollInstalled = true;
        function unlock() {
            var body = document.body;
            if (!body) { return; }
            var bs = getComputedStyle(body);
            if (bs.position === 'fixed' || bs.overflowY === 'hidden') {
                body.style.setProperty('position', 'static', 'important');
                body.style.setProperty('overflow', 'visible', 'important');
                // Debug-only breadcrumb so scripts/analyze_log.py can confirm the unlock
                // fired and measure how long the lock was in place. Only emitted on an
                // actual reassert (rare), so release builds stay quiet even though this
                // runs for every build.
                if (window.__wetterDebug) {
                    console.log('__wetter_unlock__ ' + JSON.stringify({
                        was: bs.position + '/' + bs.overflowY,
                        t: Math.round(performance.now()),
                    }));
                }
            }
        }
        unlock();
        new MutationObserver(unlock).observe(document.body, {
            attributes: true,
            attributeFilter: ['style', 'class'],
        });
        var start = performance.now();
        (function raf() {
            unlock();
            if (performance.now() - start < $UNLOCK_WINDOW_MS) {
                requestAnimationFrame(raf);
            }
        })();
    })();
""".trimIndent()

// Shown instead of the WebView's stock "Webpage not available" page when the main frame
// fails to load (no network, server down, timeout). Auto-retries via meta refresh every
// 8 seconds and immediately on tap; matches the app's day/night background so the error
// state doesn't flash a mismatched screen.
private fun errorPageHtml(isDark: Boolean, reason: String): String {
    val bg = if (isDark) "#111111" else "#fafafa"
    val fg = if (isDark) "#9e9e9e" else "#555555"
    return """
        <!doctype html><html><head>
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <meta http-equiv="refresh" content="8;url=$WEATHER_URL">
        <style>
            body { background: $bg; color: $fg; font-family: sans-serif; margin: 0;
                   height: 100vh; display: flex; align-items: center; justify-content: center; }
            a { color: inherit; text-align: center; text-decoration: none; padding: 2em; }
            small { opacity: .6 }
        </style>
        </head><body>
        <a href="$WEATHER_URL">kachelmannwetter.com is unreachable<br>
        <small>$reason &middot; retrying automatically, tap to retry now</small></a>
        </body></html>
    """.trimIndent()
}

private fun jsStringLiteral(value: String): String {
    val escaped = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
    return "\"$escaped\""
}

// Whether the system is currently in night mode, read live off the WebView's context so
// it reflects the setting at the time of each page load (the activity restarts on a
// system theme change, since uiMode isn't declared in android:configChanges).
private fun isNightMode(context: Context): Boolean =
    (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES

// Debug-only: watches for the page's scroll container becoming non-scrollable (e.g. the
// consent-dialog scroll-lock that motivated UNLOCK_SCROLL_JS above) and logs a snapshot
// to the JS console whenever that state changes. Shows up in logcat as
// "chromium: [INFO:CONSOLE:...] "__wetter_scroll_watch__ ..."" lines, which
// scripts/analyze_log.py looks for. Only logs on change (not on every poll/mutation) to
// keep it quiet in normal operation.
private val SCROLL_WATCH_JS = """
    (function() {
        if (window.__wetterScrollWatchInstalled) { return; }
        window.__wetterScrollWatchInstalled = true;
        var lastKey = null;
        function report(reason) {
            var body = document.body;
            var bs = getComputedStyle(body);
            var snapshot = {
                reason: reason,
                scrollable: document.documentElement.scrollHeight > window.innerHeight + 1,
                bodyPosition: bs.position,
                bodyOverflowY: bs.overflowY,
                htmlScrollHeight: document.documentElement.scrollHeight,
                innerHeight: window.innerHeight,
            };
            var key = snapshot.bodyPosition + '|' + snapshot.bodyOverflowY + '|' + snapshot.scrollable;
            if (key === lastKey) { return; }
            lastKey = key;
            console.log('__wetter_scroll_watch__ ' + JSON.stringify(snapshot));
        }
        report('initial');
        new MutationObserver(function() { report('mutation'); }).observe(
            document.body,
            { attributes: true, attributeFilter: ['style', 'class'] },
        );
        var tries = 0;
        var interval = setInterval(function() {
            report('poll');
            if (++tries > 20) { clearInterval(interval); }
        }, 500);
    })();
""".trimIndent()

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    WeatherWebView(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WeatherWebView(modifier: Modifier = Modifier) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var swipeRefreshLayout by remember { mutableStateOf<SwipeRefreshLayout?>(null) }
    // canGoBack() is a plain method call, not Compose state, so it must be mirrored
    // into a State explicitly (updated on every navigation) for BackHandler to react
    // to in-page navigation instead of latching to the value from first composition.
    var canGoBack by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current

    BackHandler(enabled = canGoBack) {
        webView?.goBack()
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            SwipeRefreshLayout(context).apply {
                // Compose's AndroidView holder adds the factory view without LayoutParams, so
                // it would get the ViewGroup default WRAP_CONTENT. A WebView whose
                // layoutParams.height is WRAP_CONTENT switches Chromium into grow-with-content
                // mode, where CSS percentage heights resolve against zero (found the hard way
                // in the lightningmaps sibling, where the page collapsed to its min-height
                // floors). This app currently survives because SwipeRefreshLayout measures its
                // child with exact specs — but set MATCH_PARENT explicitly on both views so
                // correct sizing doesn't hinge on that wrapper implementation detail.
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                setOnRefreshListener {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    webView?.reload()
                }
                addView(
                    WebView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        // Lets the live page be inspected via chrome://inspect on a connected
                        // computer, e.g. to diagnose why an injected CSS rule breaks scrolling.
                        val isDebuggable =
                            context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
                        if (isDebuggable) {
                            WebView.setWebContentsDebuggingEnabled(true)
                        }
                        // Page console → logcat, so injection state is visible via
                        // `adb logcat -s Wetter` even without a chrome://inspect session.
                        // Returns false (unlike the lightningmaps sibling) so chromium still
                        // emits its own "[INFO:CONSOLE]" lines — scripts/analyze_log.py greps
                        // for those.
                        webChromeClient = object : WebChromeClient() {
                            override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                                Log.d("Wetter", "${msg.message()} (${msg.sourceId()}:${msg.lineNumber()})")
                                return false
                            }
                        }
                        val isDark = isNightMode(context)
                        // Loaded once per WebView instance rather than per navigation.
                        val darkReaderInjectJs = if (isDark) {
                            val bundle = context.assets.open("darkreader.js").bufferedReader().use { it.readText() }
                            injectDarkReaderJs(bundle)
                        } else {
                            null
                        }
                        if (isDark) {
                            // Avoids a white flash of the WebView's own surface before the page
                            // has painted and Dark Reader has kicked in.
                            setBackgroundColor(Color.parseColor("#111111"))
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest,
                            ): Boolean {
                                val url = request.url
                                if (url.scheme == "http" || url.scheme == "https") return false
                                // Non-http(s) links (mailto:, tel:, intent:, ...) can't be
                                // loaded by the WebView itself; hand them to the system.
                                return try {
                                    view.context.startActivity(Intent(Intent.ACTION_VIEW, url))
                                    true
                                } catch (_: ActivityNotFoundException) {
                                    true
                                }
                            }

                            override fun onReceivedError(
                                view: WebView,
                                request: WebResourceRequest,
                                error: WebResourceError,
                            ) {
                                super.onReceivedError(view, request, error)
                                // Subresources (ads, trackers) fail all the time — only a failed
                                // main document warrants the error screen.
                                if (!request.isForMainFrame) return
                                view.loadDataWithBaseURL(
                                    null,
                                    errorPageHtml(isNightMode(view.context), error.description.toString()),
                                    "text/html",
                                    "utf-8",
                                    null,
                                )
                            }

                            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                view.evaluateJavascript(INJECT_HIDE_STYLE_JS, null)
                                if (darkReaderInjectJs != null) {
                                    view.evaluateJavascript(darkReaderInjectJs, null)
                                    view.evaluateJavascript(ENABLE_DARKREADER_JS, null)
                                }
                            }

                            override fun onPageFinished(view: WebView, url: String?) {
                                super.onPageFinished(view, url)
                                // Set the debug flag first so UNLOCK_SCROLL_JS's breadcrumb logging
                                // is active by the time it runs (and stays off in release builds).
                                if (isDebuggable) {
                                    view.evaluateJavascript("window.__wetterDebug = true;", null)
                                }
                                view.evaluateJavascript(INJECT_HIDE_STYLE_JS, null)
                                view.evaluateJavascript(CLASSIFY_MODEL_BUTTONS_JS, null)
                                if (darkReaderInjectJs != null) {
                                    // Re-asserted after load, same as the hide style above, in case
                                    // late page scripts touched <head> after our first injection. The
                                    // "if (!window.DarkReader)" guard keeps this from re-parsing the
                                    // ~346KB bundle a second time within the same document.
                                    view.evaluateJavascript(darkReaderInjectJs, null)
                                    view.evaluateJavascript(ENABLE_DARKREADER_JS, null)
                                }
                                if (darkReaderInjectJs != null) {
                                    view.evaluateJavascript(AUTO_SITE_DARK_JS, null)
                                }
                                view.evaluateJavascript(UNLOCK_SCROLL_JS, null)
                                if (isDebuggable) {
                                    view.evaluateJavascript(SCROLL_WATCH_JS, null)
                                }
                                canGoBack = view.canGoBack()
                                swipeRefreshLayout?.isRefreshing = false
                            }
                        }
                        loadUrl(WEATHER_URL)
                        webView = this
                    }
                )
                swipeRefreshLayout = this
            }
        },
        onRelease = {
            webView?.destroy()
            webView = null
        },
    )
}
