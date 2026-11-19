package com.optionslab.app.ui.screens

import com.optionslab.app.data.NtfyReader
import com.optionslab.engine.signals.NtfyRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 19 Nov 2026 (audit batch 2: ntfy's live gate): the ntfy page's Trade live switch asks the PIN only with an access token or Boss's
 * own server set; with only the topic (or the settings not read yet) it is refused and said; off is always one tap.
 */
class NtfyLiveTapTest {
    private fun cfg(token: String? = null, server: String = NtfyRules.DEFAULT_SERVER) = NtfyReader.Config("a-long-random-topic", server, token, "")

    @Test fun onAsksThePinOnlyWithATokenOrOwnServerOffIsAlwaysOneTap() {
        assertEquals(NtfyLiveTap.NEEDS_TOKEN, ntfyLiveTap(true, cfg()))
        assertEquals("not read yet: refused", NtfyLiveTap.NEEDS_TOKEN, ntfyLiveTap(true, null))
        assertEquals(NtfyLiveTap.ASK_PIN, ntfyLiveTap(true, cfg(token = "tk_123")))
        assertEquals(NtfyLiveTap.ASK_PIN, ntfyLiveTap(true, cfg(server = "https://ntfy.example.org")))
        assertEquals(NtfyLiveTap.OFF, ntfyLiveTap(false, cfg()))
        assertEquals(NtfyLiveTap.OFF, ntfyLiveTap(false, null))
    }

    @Test fun onlyATokenOrOwnServerSecuresTheTopic() {
        assertFalse(NtfyReader.secured(cfg()))
        assertTrue(NtfyReader.secured(cfg(token = "tk_123")))
        assertTrue(NtfyReader.secured(cfg(server = "https://ntfy.example.org")))
        assertTrue(NtfyReader.LIVE_NEEDS_TOKEN.contains("access token"))
        // The public ntfy.sh however it is written is never "your own server".
        for (s in listOf("https://ntfy.sh", "https://NTFY.sh:443", "https://www.ntfy.sh/", "https://ntfy.sh/path", "not a url"))
            assertFalse(s, NtfyReader.secured(cfg(server = s)))
    }
}
