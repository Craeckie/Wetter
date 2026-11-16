package com.example.wetter

// Routing around a network the site refuses to serve (an "Access Denied" edge block, which
// CDNs apply to whole VPN/datacenter IP ranges). The user lists proxies, one per line; the
// app starts on the sticky rung (direct by default) and, when the site blocks the current
// rung, moves to the next one it hasn't tried yet. Pure Kotlin so it is unit-testable; the
// WebView side lives in WebViewProxy.kt and MainActivity.kt.

// Parses the user's proxy list. One proxy per line, `scheme://host[:port]` or bare
// `host[:port]`; blank lines and `#` comments are skipped. Schemes: socks5/socks (SOCKS5,
// the default for a bare host, port 1080) and http/https (port 80/443). Returns the rules in
// the form androidx.webkit's ProxyConfig takes (`socks://` is SOCKS5 there). Lines that
// don't parse are dropped -- invalidLines() reports them for the settings dialog.
object ProxyList {
    private val LINE = Regex("""^(?:([a-zA-Z0-9]+)://)?([A-Za-z0-9.\-]+|\[[0-9a-fA-F:.]+])(?::(\d{1,5}))?/?$""")

    fun parse(text: String): List<String> = lines(text).mapNotNull(::parseLine)

    fun invalidLines(text: String): List<String> = lines(text).filter { parseLine(it) == null }

    private fun lines(text: String): List<String> =
        text.lines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }

    private fun parseLine(line: String): String? {
        val m = LINE.matchEntire(line) ?: return null
        val (schemeRaw, host, portRaw) = m.destructured
        val (scheme, defaultPort) = when (schemeRaw.lowercase()) {
            "", "socks", "socks5", "socks5h" -> "socks" to 1080
            "http" -> "http" to 80
            "https" -> "https" to 443
            else -> return null
        }
        val port = if (portRaw.isEmpty()) defaultPort else portRaw.toInt()
        if (port !in 1..65535) return null
        return "$scheme://$host:$port"
    }
}

// Rung 0 is a direct connection, rung i > 0 is proxies[i - 1].
class ProxyRotation(
    val proxies: List<String>,
    activeRung: Int,
    private val episodeMs: Long = EPISODE_MS,
) {
    val rungCount: Int get() = proxies.size + 1

    var active: Int = if (activeRung in 0 until rungCount) activeRung else 0
        private set

    private val tried = mutableSetOf(active)
    private var lastFailureAt: Long? = null

    // The ProxyConfig rule for the active rung, or null for direct.
    val activeRule: String? get() = if (active == 0) null else proxies[active - 1]

    // The active rung was blocked or could not connect. Moves to the next rung not yet tried
    // in this episode and returns true, or returns false when every rung has been tried --
    // the caller then shows the block/error page, and the next failure starts a new episode
    // (so a retry from that page walks the whole list again). Failures more than episodeMs
    // apart are separate episodes: a block an hour after a good load must not count the
    // rungs that were tried back then.
    fun onFailure(nowMs: Long): Boolean {
        val last = lastFailureAt
        if (last == null || nowMs - last > episodeMs) {
            tried.clear()
            tried += active
        }
        lastFailureAt = nowMs
        for (step in 1 until rungCount) {
            val candidate = (active + step) % rungCount
            if (tried.add(candidate)) {
                active = candidate
                return true
            }
        }
        // Exhausted: forget this episode so the next failure (a retry) starts fresh.
        lastFailureAt = null
        return false
    }

    companion object {
        const val EPISODE_MS = 60_000L
    }
}
