package com.optionslab.app.data

import com.optionslab.app.ira.IraHub
import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.ira.PositionSync
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode

/**
 * Zerodha reconciliation (round 4, report only), against the fake Kite: a position the app does not follow and a protection
 * on a position Zerodha shows flat are said once they stood in two checks, noted once in Jarvis's activity, and nothing is
 * ever sent to Zerodha; Jarvis's answers ("are my positions in sync", "what's my slippage") never on a locked phone.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class ZerodhaSyncTest : RobolectricTest() {
    private lateinit var kite: FakeKite
    private val sym = "NIFTY26OCT24500CE"
    private val other = "BANKNIFTY26OCT52000PE"

    @Before fun up() {
        assumeFalse("IraGoldAlgo has no Zerodha sync", com.optionslab.app.BuildConfig.GOLD)
        kite = FakeKite()
        kite.instruments += FakeKite.Ins(12_345_678, sym, "NIFTY", AutomationSupport.expiryAfter(20), 24_500.0, "CE", 75)
        kite.quote("NFO:$sym", 100.0, 99.95, 100.05)
        kite.login()
        AutomationSupport.liveSettings()
    }

    @After fun down() {
        if (::kite.isInitialized) kite.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun check(): List<PositionSync.Mismatch>? { Thread.sleep(5); return runBlocking { ZerodhaSync.check() } }

    @Test fun aPositionTheAppDoesNotFollowIsSaidAfterTwoChecksAndNothingIsSent() {
        kite.position(other, -30, 210.0, product = "NRML")
        val writes = kite.writes.size
        assertEquals("one check may be a moment's lag", emptyList<PositionSync.Mismatch>(), check())
        val m = checkNotNull(check()).single()
        assertEquals(PositionSync.Kind.UNTRACKED, m.kind); assertEquals(other, m.symbol); assertEquals(-30, m.brokerQty)
        assertTrue(ZerodhaSync.line(), ZerodhaSync.line().contains("1 mismatch") && !ZerodhaSync.line().contains("₹"))
        // Noted once in Jarvis's activity (never spoken), however many checks follow.
        check(); check()
        if (com.optionslab.app.BuildConfig.JARVIS)
            assertEquals(1, com.optionslab.app.ira.IraActivity.entries().count { it.what.startsWith("Zerodha sync:") })
        assertEquals("report only: nothing placed, changed or cancelled", writes, kite.writes.size)
    }

    @Test fun aProtectionOnAPositionZerodhaShowsFlat() {
        kite.position(sym, 75, 100.0)
        val msg = runBlocking { Protections.protectLive(sym, "NFO", "NRML", 75, 100.0, 90.0, null, null) }
        assertTrue(msg, msg.startsWith("Protected"))
        check(); assertEquals(emptyList<PositionSync.Mismatch>(), check())
        kite.flat()
        val writes = kite.writes.size
        check()
        val m = checkNotNull(check()).single()
        assertEquals(PositionSync.Kind.FLAT_AT_BROKER, m.kind); assertEquals("Protection", m.who)
        assertEquals(writes, kite.writes.size)
    }

    @Test fun notLoggedInNothingIsChecked() {
        Broker.dropCreds()
        com.optionslab.app.security.SecurePrefs.putAll(mapOf("kite.accessToken" to null))
        assertTrue(!Broker.loggedIn)
        assertEquals(null, check())
        assertTrue(ZerodhaSync.line(), ZerodhaSync.line().startsWith("Zerodha sync: not checked yet"))
    }

    @Test fun jarvisAnswersOnlyOnAnUnlockedPhone() {
        fun ask(q: String): String {
            val start = IraHub.state.value.messages.maxOfOrNull { it.id } ?: 0L
            IraHub.ask(q)
            val end = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < end && IraHub.state.value.messages.none { it.fromIra && it.id > start }) Thread.sleep(10)
            return IraHub.state.value.messages.last().text
        }
        val km = org.robolectric.Shadows.shadowOf(context.getSystemService(android.app.KeyguardManager::class.java))
        km.setKeyguardLocked(true)
        try {
            assertEquals("Unlock the phone for that, Boss.", ask("are my positions in sync"))
            assertEquals("Unlock the phone for that, Boss.", ask("what's my slippage"))
        } finally { km.setKeyguardLocked(false) }
        val sync = ask("are my positions in sync")
        assertTrue(sync, sync.startsWith("I have not compared the app with Zerodha yet"))
        val slip = ask("what's my slippage on ORB")
        assertTrue(slip, slip.startsWith("No live fills measured yet for slippage on ORB"))
    }
}
