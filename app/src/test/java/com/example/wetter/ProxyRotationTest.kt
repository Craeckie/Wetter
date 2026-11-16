package com.example.wetter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyListTest {
    @Test
    fun parsesSchemesAndDefaults() {
        val text = """
            socks5://proxy.example.org:1080
            socks://10.0.0.1
            proxy-b.example.org   # bare host -> SOCKS5 on 1080

            http://h.example.org:3128
            https://h.example.org
        """.trimIndent()
        assertEquals(
            listOf(
                "socks://proxy.example.org:1080",
                "socks://10.0.0.1:1080",
                "socks://proxy-b.example.org:1080",
                "http://h.example.org:3128",
                "https://h.example.org:443",
            ),
            ProxyList.parse(text),
        )
    }

    @Test
    fun reportsInvalidLines() {
        val text = "ftp://x.org\nok.example.org:1080\nhost:99999\nnot a host"
        assertEquals(listOf("socks://ok.example.org:1080"), ProxyList.parse(text))
        assertEquals(listOf("ftp://x.org", "host:99999", "not a host"), ProxyList.invalidLines(text))
    }
}

class ProxyRotationTest {
    private val proxies = listOf("socks://a:1080", "socks://b:1080")

    @Test
    fun startsOnStickyRungAndClampsOutOfRange() {
        assertEquals("socks://b:1080", ProxyRotation(proxies, 2).activeRule)
        assertNull(ProxyRotation(proxies, 5).activeRule)
        assertNull(ProxyRotation(emptyList(), 1).activeRule)
    }

    @Test
    fun walksEveryRungOnceThenGivesUp() {
        val r = ProxyRotation(proxies, 0)
        assertTrue(r.onFailure(1_000)); assertEquals(1, r.active)
        assertTrue(r.onFailure(2_000)); assertEquals(2, r.active)
        assertFalse(r.onFailure(3_000)); assertEquals(2, r.active)
        // A retry after giving up starts a fresh walk from the current rung.
        assertTrue(r.onFailure(4_000)); assertEquals(0, r.active)
    }

    @Test
    fun wrapsAroundFromAStickyProxy() {
        val r = ProxyRotation(proxies, 2)
        assertTrue(r.onFailure(0)); assertEquals(0, r.active)
        assertTrue(r.onFailure(10)); assertEquals(1, r.active)
        assertFalse(r.onFailure(20))
    }

    @Test
    fun failuresFarApartAreSeparateEpisodes() {
        val r = ProxyRotation(proxies, 0, episodeMs = 1_000)
        assertTrue(r.onFailure(0)); assertEquals(1, r.active)
        assertTrue(r.onFailure(100)); assertEquals(2, r.active)
        // Much later: rung 2 got blocked too; rungs 0 and 1 are fair game again.
        assertTrue(r.onFailure(10_000)); assertEquals(0, r.active)
    }

    @Test
    fun directOnlyHasNothingToSwitchTo() {
        assertFalse(ProxyRotation(emptyList(), 0).onFailure(0))
    }
}
