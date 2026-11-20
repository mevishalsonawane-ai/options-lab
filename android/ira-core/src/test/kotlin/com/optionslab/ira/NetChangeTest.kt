package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 20 Nov 2026 (audit: the relay dead for up to 45 s after a network change): [NetChange]. */
class NetChangeTest {
    @Test fun theFirstNetworkIsNotAChangeAnotherOneIs() {
        var s = NetChange.State()
        val (a, first) = NetChange.available(s, "wifi-100")
        assertFalse(first, "the network the callback reports when registered")
        s = a
        val (b, same) = NetChange.available(s, "wifi-100")
        assertFalse(same, "the same network reported again")
        s = b
        val (c, moved) = NetChange.available(s, "mobile-101")
        assertTrue(moved, "Wi-Fi to mobile data")
        assertEquals("mobile-101", c.current)
    }

    @Test fun theSameNetworkBackAfterItWasLostIsAChange() {
        var s = NetChange.available(NetChange.State(), "wifi-100").first
        s = NetChange.lost(s, "mobile-7")
        assertFalse(s.lost, "another network's loss changes nothing")
        s = NetChange.lost(s, "wifi-100")
        assertTrue(s.lost)
        val (t, back) = NetChange.available(s, "wifi-100")
        assertTrue(back, "its sockets did not survive the loss")
        assertFalse(t.lost)
        // Lost before anything was seen: the first network after it is still not a change (nothing to reopen).
        val (_, none) = NetChange.available(NetChange.lost(NetChange.State(), "x"), "wifi-1")
        assertFalse(none)
    }
}
