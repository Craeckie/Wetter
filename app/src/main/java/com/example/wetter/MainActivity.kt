package com.example.wetter

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
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
    // Ad slots. These reserve layout height even when no ad loads (none does in
    // this WebView), so leaving them in shows up as large empty bands -- most
    // visibly the top "billboard" margin. Selectors from the maintained public
    // lists: uBlock uAssets (dkpw-billboard-margin*, kw-ad-right, #meteosafe,
    // pflotsh promo) and EasyList Germany (.dkpw).
    ".dkpw-billboard-margin",
    ".dkpw-billboard-margin-fixed",
    ".dkpw",
    ".kw-ad-right",
    "#meteosafe",
    "[href=\"https://pflotsh.com\"]",
)

private val HIDE_CSS =
    HIDE_SELECTORS.joinToString(separator = ",\n") { it } + " { display: none !important; }"

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
private val ENABLE_DARKREADER_JS = """
    (function () {
        if (!window.DarkReader) { return; }
        // The standalone bundle can't use the browser extension's privileged
        // cross-origin fetch for stylesheets; routing through the page's own fetch
        // at least covers same-origin sheets (this page's own CSS).
        DarkReader.setFetchMethod(window.fetch);
        DarkReader.enable({ brightness: 100, contrast: 100, sepia: 0 });
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
                setOnRefreshListener {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    webView?.reload()
                }
                addView(
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        // Lets the live page be inspected via chrome://inspect on a connected
                        // computer, e.g. to diagnose why an injected CSS rule breaks scrolling.
                        val isDebuggable =
                            context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
                        if (isDebuggable) {
                            WebView.setWebContentsDebuggingEnabled(true)
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
                                if (darkReaderInjectJs != null) {
                                    // Re-asserted after load, same as the hide style above, in case
                                    // late page scripts touched <head> after our first injection. The
                                    // "if (!window.DarkReader)" guard keeps this from re-parsing the
                                    // ~346KB bundle a second time within the same document.
                                    view.evaluateJavascript(darkReaderInjectJs, null)
                                    view.evaluateJavascript(ENABLE_DARKREADER_JS, null)
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
