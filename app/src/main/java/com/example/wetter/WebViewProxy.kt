package com.example.wetter

import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature

// Process-wide proxy override for every WebView in the app (androidx.webkit ProxyController,
// needs a WebView that supports PROXY_OVERRIDE -- any current Chromium WebView does).
// The rotation state (which proxy is active) is persisted, so a proxy that got the site to
// load stays in use on the next launch instead of hitting the block again first.
object WebViewProxy {
    private const val PREFS_NAME = "wetter_prefs"
    private const val KEY_LIST = "proxy_list"
    private const val KEY_ACTIVE = "proxy_active"

    val isSupported: Boolean get() = WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)

    fun listText(context: Context): String =
        prefs(context).getString(KEY_LIST, "").orEmpty()

    fun load(context: Context): ProxyRotation {
        val proxies = if (isSupported) ProxyList.parse(listText(context)) else emptyList()
        return ProxyRotation(proxies, prefs(context).getInt(KEY_ACTIVE, 0))
    }

    fun saveList(context: Context, text: String) {
        prefs(context).edit().putString(KEY_LIST, text).apply()
    }

    fun saveActive(context: Context, rung: Int) {
        prefs(context).edit().putInt(KEY_ACTIVE, rung).apply()
    }

    // Points all WebViews at the rotation's active rung, then runs onApplied on the main
    // thread -- the override only affects requests started after it is applied, so the
    // caller (re)loads the page from onApplied.
    fun apply(context: Context, rotation: ProxyRotation, onApplied: () -> Unit) {
        if (!isSupported) {
            onApplied()
            return
        }
        val rule = rotation.activeRule
        val executor = ContextCompat.getMainExecutor(context)
        val controller = ProxyController.getInstance()
        Log.i("Wetter", "proxy: ${rule ?: "direct"}")
        if (rule == null) {
            controller.clearProxyOverride(executor, onApplied)
        } else {
            val config = ProxyConfig.Builder().addProxyRule(rule).build()
            controller.setProxyOverride(config, executor, onApplied)
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
