package com.optionslab.app.data

import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.testing.Background
import com.optionslab.app.testing.RobolectricTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * 18 Oct, review: a live MCX position kept as held with no Zerodha session keeps the MCX watch (and its wake lock) running
 * every MCX day - kept so, an open position comes first - and Boss is told once a day that a login is needed to confirm it.
 */
class McxLoginNoticeTest : RobolectricTest() {
    private val today = LocalDate.of(2026, 10, 19)

    @Before fun up() {
        McxMarket.resetLoginNoticeForTest()
        Background.grantNotifications(context)
        AppSettings.save(AppSettings.load().copy(otherAlerts = true))
    }

    @Test fun dueOnlyWithNoSessionAndAFlagFromBeforeToday() {
        assertTrue(McxMarket.loginNoticeDue("2026-10-16", today, loggedIn = false, toldOn = null))
        assertFalse("a session reads Zerodha itself", McxMarket.loginNoticeDue("2026-10-16", today, loggedIn = true, toldOn = null))
        assertFalse("nothing held", McxMarket.loginNoticeDue(null, today, loggedIn = false, toldOn = null))
        assertFalse("nothing held", McxMarket.loginNoticeDue("", today, loggedIn = false, toldOn = null))
        assertFalse("seen today: not stale", McxMarket.loginNoticeDue(today.toString(), today, loggedIn = false, toldOn = null))
        assertFalse("told today already", McxMarket.loginNoticeDue("2026-10-16", today, loggedIn = false, toldOn = today))
        assertTrue("told yesterday: told again today", McxMarket.loginNoticeDue("2026-10-16", today, loggedIn = false, toldOn = today.minusDays(1)))
        assertTrue("an older flag that is not a day counts as stale", McxMarket.loginNoticeDue("true", today, loggedIn = false, toldOn = null))
    }

    @Test fun bossIsToldOnceADayAndTheWatchKeepsThePosition() {
        val yesterday = Market.today().minusDays(1)
        SecurePrefs.put("mcx.liveHeld", yesterday.toString())
        assertTrue(McxMarket.loginNoticeIfDue(context, loggedIn = false))
        val posted = Background.notifications(context).allNotifications.filter { Background.title(it) == McxMarket.LOGIN_TITLE }
        assertEquals(1, posted.size)
        assertFalse("once a day", McxMarket.loginNoticeIfDue(context, loggedIn = false))
        // A restart (memory gone) still knows it was said today.
        McxMarket.resetLoginNoticeForTest()
        assertFalse("kept across a restart", McxMarket.loginNoticeIfDue(context, loggedIn = false))
        // The watch keeps the position: still held, never dropped on a guess.
        assertTrue(McxMarket.liveExposure())
        assertTrue(McxMarket.heldNow())
    }

    @Test fun noNoticeWithASessionOrNothingHeld() {
        SecurePrefs.put("mcx.liveHeld", Market.today().minusDays(2).toString())
        assertFalse(McxMarket.loginNoticeIfDue(context, loggedIn = true))
        SecurePrefs.put("mcx.liveHeld", null)
        assertFalse(McxMarket.loginNoticeIfDue(context, loggedIn = false))
    }
}
