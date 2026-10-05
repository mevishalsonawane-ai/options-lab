package com.optionslab.app.work

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A tapped notification's card: what goes into the intent and back, and where each kind takes the app (pure JVM). */
class NoticeCardTest {
    @After fun down() = NoticeCards.forgetAll()

    private fun roundTrip(c: NoticeCard): NoticeCard? = NoticeCards.toExtras(c).let { m -> NoticeCards.fromExtras { m[it] } }

    @Test fun aCardSurvivesTheIntent() {
        val plain = NoticeCard(2005, Notifier.SCHEDULE, "Log in to Zerodha for today", "Yesterday's session ended at 06:00.", 1_759_640_000_000L,
            tab = "broker", setting = "broker.login")
        assertEquals(plain, roundTrip(plain))
        val asks = NoticeCard(7401, "jarvis", "Boss, a goal is broken", "Shall I switch the kill switch on?", 1L, tab = "almanac", action = 123_456_789_012L)
        assertEquals(asks, roundTrip(asks))
        val strategy = NoticeCard(7301, Notifier.IRA, "Jarvis found a strategy", "12 trades", 2L, proposal = 7L)
        assertEquals(strategy, roundTrip(strategy))
        val position = NoticeCard(30_001, Notifier.BUY, "NIFTY · Live", "LONG 75 @ 120.00", 3L, tab = "trade", close = "Live|NIFTY26OCT24500PE")
        assertEquals(position, roundTrip(position))
        assertEquals("Live", position.closeVenue); assertEquals("NIFTY26OCT24500PE", position.closeSymbol)
        val entry = NoticeCard(6960, Notifier.APPROVAL, "ORB: approve BUY", "Approve by 09:40 or it lapses.", 4L, tab = "almanac", approve = "orb")
        assertEquals(entry, roundTrip(entry))
        assertTrue(NoticeCards.asks(entry))
    }

    @Test fun onlyTheAppsOwnFieldsAndNothingElse() {
        val m = NoticeCards.toExtras(NoticeCard(1, "risk", "t", "x", 5L))
        assertEquals(setOf("notice.id", "notice.kind", "notice.title", "notice.text", "notice.at"), m.keys)
        // A long text is cut: an intent's extras stay small.
        val long = NoticeCards.toExtras(NoticeCard(1, "risk", "t", "y".repeat(10_000), 5L))
        assertEquals(NoticeCards.MAX_TEXT, long.getValue("notice.text").length)
    }

    @Test fun aMalformedOrForeignCardIsDropped() {
        assertNull(NoticeCards.fromExtras { null })
        assertNull("no id", NoticeCards.fromExtras { k -> mapOf("notice.title" to "t", "notice.text" to "x", "notice.at" to "1")[k] })
        assertNull("no time", NoticeCards.fromExtras { k -> mapOf("notice.id" to "1", "notice.title" to "t", "notice.text" to "x")[k] })
        // An unknown settings key or close venue is not trusted: no row, no button.
        val odd = NoticeCards.fromExtras { k ->
            mapOf("notice.id" to "1", "notice.title" to "t", "notice.text" to "x", "notice.at" to "1",
                "notice.setting" to "security.pin.off", "notice.close" to "Elsewhere|X", "notice.action" to "abc", "notice.approve" to "live.on")[k]
        }!!
        assertNull(odd.setting); assertNull(odd.close); assertNull(odd.action); assertNull(odd.approve)
        assertFalse(NoticeCards.asks(odd))
    }

    @Test fun settingsCardsGoToTheirRowEverythingElseToItsPage() {
        assertEquals(NoticeCards.Route.Setting("broker", "broker.login"),
            NoticeCards.route(NoticeCard(1, "schedule", "t", "x", 1L, tab = "broker", setting = "broker.login")))
        assertEquals(NoticeCards.Route.Setting("security", "security.backup"),
            NoticeCards.route(NoticeCard(1, "approval", "t", "x", 1L, tab = "almanac", setting = "security.backup")))
        assertEquals(NoticeCards.Route.Setting("jarvis", "jarvis.model"), NoticeCards.route(NoticeCard(1, "ira", "t", "x", 1L, setting = "jarvis.model")))
        assertEquals(NoticeCards.Route.Page("trade"), NoticeCards.route(NoticeCard(1, "buy", "t", "x", 1L, tab = "trade")))
        assertEquals(NoticeCards.Route.Page(null), NoticeCards.route(NoticeCard(1, "risk", "t", "x", 1L, setting = "nowhere")))
        // Every key names a page the Settings tab has.
        val pages = setOf("ira", "jarvis", "broker", "alarms", "risk", "signal", "ic", "sizing", "costs", "lots", "data", "security", "schedule", "notes")
        NoticeCards.SETTINGS.forEach { (k, page) -> assertTrue(k, page in pages); assertTrue(k, k.startsWith("$page.")) }
    }

    @Test fun eachNotificationHasItsOwnRequestCode() {
        val a = NoticeCard(7401, "jarvis", "t", "x", 1L)
        val b = NoticeCard(7402, "jarvis", "t", "x", 1L)
        val c = NoticeCard(7401, "approval", "t", "x", 1L)
        assertNotEquals(NoticeCards.requestCode(a), NoticeCards.requestCode(b))
        assertNotEquals(NoticeCards.requestCode(a), NoticeCards.requestCode(c))
        assertEquals("the same notification posted again replaces its own", NoticeCards.requestCode(a), NoticeCards.requestCode(a.copy(text = "y", at = 9L)))
    }

    @Test fun theWholeCardIsKeptInMemoryForWhatALockedPhoneShowedShort() {
        val full = NoticeCard(7403, "jarvis", "Boss, target reached", "NIFTY 24500 PE is up Rs 1,200.", 42L, action = 9L)
        NoticeCards.keep(full)
        val shown = full.copy(title = "Jarvis", text = "It's in the chat.")
        assertEquals(full, NoticeCards.resolve(shown))
        assertEquals("another notification is not confused with it", shown.copy(at = 43L), NoticeCards.resolve(shown.copy(at = 43L)))
        NoticeCards.forgetAll()
        assertEquals(shown, NoticeCards.resolve(shown))
    }

    @Test fun theMorningCheckPointsAtWhatNeedsSetting() {
        assertEquals("broker.login", NoticeCards.morningSetting(listOf("• Microphone permission: not working."), needsLogin = true))
        assertEquals("jarvis.voice", NoticeCards.morningSetting(listOf("✓ Funds", "• Microphone permission: not working."), needsLogin = false))
        assertEquals("jarvis.voice", NoticeCards.morningSetting(listOf("• Voice: not working."), needsLogin = false))
        assertEquals("security.backup", NoticeCards.morningSetting(listOf("• You have never made a backup: your trades live only on this phone."), needsLogin = false))
        assertNull(NoticeCards.morningSetting(listOf("✓ Funds", "• Outlook: NIFTY up"), needsLogin = false))
    }
}
