package com.example.wetter

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
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
import androidx.compose.ui.viewinterop.AndroidView
import com.example.wetter.ui.theme.AppTheme

private const val WEATHER_URL = "https://kachelmannwetter.com/de/wetter/2892794-city"

// Elements to strip from the page, carried over from the equivalent uBlock Origin
// cosmetic filters (kachelmannwetter.com##...).
private val HIDE_SELECTORS = listOf(
    "#countrydetection",
    "div.weather-infos",
    ".geonames-info",
    ".alert-dismissible.alert-default.alert",
    "div.md-mob-margin.mdcss-mobile",
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

private fun jsStringLiteral(value: String): String {
    val escaped = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
    return "\"$escaped\""
}

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
    // canGoBack() is a plain method call, not Compose state, so it must be mirrored
    // into a State explicitly (updated on every navigation) for BackHandler to react
    // to in-page navigation instead of latching to the value from first composition.
    var canGoBack by remember { mutableStateOf(false) }

    BackHandler(enabled = canGoBack) {
        webView?.goBack()
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
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
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        super.onPageFinished(view, url)
                        view.evaluateJavascript(INJECT_HIDE_STYLE_JS, null)
                        canGoBack = view.canGoBack()
                    }
                }
                loadUrl(WEATHER_URL)
                webView = this
            }
        },
        onRelease = { it.destroy() },
    )
}
