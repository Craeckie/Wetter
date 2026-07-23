package com.example.wetter

import android.app.Application
import android.content.pm.ApplicationInfo
import java.net.InetAddress

// The host every launch loads from: both SEARCH_URL and stored city URLs live on
// kachelmannwetter.com. All of the page's JS/CSS subresources are served from Chromium's
// disk cache on repeat launches (transfer:0 -- see docs/startup-performance.md), so the
// only request that actually hits the network is the main HTML document itself; warming
// this host's DNS is what that request benefits from. Kept as a list so a distinct radar/
// image CDN host can be added if a capture ever shows one being fetched uncached.
private val WARM_HOSTS = listOf("kachelmannwetter.com")

class WetterApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Earliest app-code marker on the cold-start timeline (debug builds only).
        val debug = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        StartupTrace.log(debug, "WetterApplication.onCreate")

        // Resolve the weather host's DNS off the critical path so the WebView's own connect
        // (~400ms in, per docs/startup-performance.md) finds it already cached. Chromium falls
        // back to the system (netd) resolver on devices that log "Failed to read DnsConfig",
        // so this plain java.net resolve warms the same cache its socket connect will hit.
        // Low-priority daemon thread, best-effort only: it must never delay or crash startup.
        Thread {
            for (host in WARM_HOSTS) {
                try {
                    InetAddress.getAllByName(host)
                } catch (_: Exception) {
                    // Best-effort warm-up; the real connection attempt retries normally.
                }
            }
        }.apply {
            name = "dns-warmup"
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }.start()
    }
}
