package com.optionslab.app.ira

import android.app.AlarmManager
import android.app.Application
import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.testing.Background
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.app.work.DailyReports
import com.optionslab.app.work.Heartbeat
import com.optionslab.app.work.Jobs
import com.optionslab.engine.IST
import com.optionslab.ira.Overheard
import com.optionslab.ira.SundayReview
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import java.time.LocalDate
import java.time.LocalTime

/**
 * Jarvis's spoken Sunday review (TODO I15; 20 Oct, enhancement round 2), the app's glue: a locked phone (or one whose lock
 * cannot be read) never hears a figure - only a "ready" notification with none in it; an unlocked one hears it whole; it is
 * made on Sundays only, once, under the "Weekly review" switch; and the Sunday alarm that runs it leaves the position
 * watch's own alarms and service exactly as they were.
 */
class IraSundayReviewTest : RobolectricTest() {
    // The week of Mon 13 Oct 2025 (Background.WED is Wed 15 Oct): Saturday 18 and Sunday 19 Oct.
    private val mon = LocalDate.of(2025, 10, 13)
    private val sat = mon.plusDays(5)
    private val sun = mon.plusDays(6)
    private val km get() = shadowOf(context.getSystemService(android.app.KeyguardManager::class.java))
    private val am get() = shadowOf(context.getSystemService(AlarmManager::class.java))

    private val text = SundayReview.compose(mon,
        listOf(SundayReview.Trade(SundayReview.Book.PAPER, "Hero (expiry)", mon.plusDays(1), 2_000.0, 40.0),
            SundayReview.Trade(SundayReview.Book.PAPER, "Solo (midday)", mon.plusDays(2), -500.0, 40.0)),
        emptyList()) { it.dayOfWeek.value <= 5 }.text

    @Before fun up() {
        IraHub.init(context); IraHub.awaitLoadedBlocking()
        Background.grantNotifications(context)
        Background.calendar(Background.WED)
        JarvisSpeaker.lastAloud = null
    }

    @After fun down() {
        km.setKeyguardLocked(false)
        JarvisSpeaker.lastAloud = null
        // Market.testClock back to the real clock, the calendar and the banners cleared.
        Background.reset()
    }

    private fun reviews() = IraHub.state.value.messages.count { it.fromIra && it.text.startsWith("Boss, your Sunday review") }
    private fun alarms(action: String) = am.scheduledAlarms.filter { shadowOf(it.operation).savedIntent.action == action }
    private fun at(day: LocalDate, t: LocalTime) = day.atTime(t).atZone(IST).toInstant().toEpochMilli()

    // ---- the lock ----

    @Test fun aLockedPhoneNeverHearsTheFiguresOnlyAPlainReadyNotice() {
        assertTrue(text, Overheard.holdsAccount(text))
        km.setKeyguardLocked(true)
        assertTrue(JarvisVoice.lockedSafe())
        assertEquals(IraSundayReview.Delivered.NOTIFIED, IraSundayReview.deliver(context, text))
        assertNull("nothing said aloud", JarvisSpeaker.lastAloud)
        val n0 = Background.notifications(context).getNotification(IraSundayReview.NOTIFY_ID)
        assertNotNull("the plain ready notice", n0)
        val n = n0!!
        val shown = "${Background.title(n)} ${Background.text(n)}"
        assertEquals(SundayReview.READY_TITLE, Background.title(n))
        assertEquals(SundayReview.READY_TEXT, Background.text(n))
        assertFalse(shown, Regex("\\d|₹|Rs").containsMatchIn(shown))
        assertFalse(shown, Overheard.holdsAccount(shown))
    }

    @Test fun aLockThatCannotBeReadCountsAsLocked() {
        val was = IraHub.swapAppForTest(null)
        try {
            assertEquals(IraSundayReview.Delivered.NOTIFIED, IraSundayReview.deliver(context, text))
        } finally { IraHub.swapAppForTest(was) }
        assertNull(JarvisSpeaker.lastAloud)
    }

    @Test fun askedOnALockedPhoneItSaysOnlyToUnlock() = runBlocking {
        val said = IraSundayReview.answer(locked = true)
        assertFalse(said, Overheard.holdsAccount(said))
        assertTrue(said, said.startsWith("Unlock the phone"))
    }

    @Test fun anUnlockedPhoneHearsItWholeWithNoNotice() {
        assumeTrue(com.optionslab.app.BuildConfig.JARVIS)
        // Review: quiet hours switched off for the test (they follow the wall clock), and back on after.
        JarvisVoice.quietHours = false
        try {
            assertFalse(JarvisVoice.lockedSafe())
            assertEquals(IraSundayReview.Delivered.SPOKEN, IraSundayReview.deliver(context, text))
        } finally { JarvisVoice.quietHours = true }
        val said = JarvisSpeaker.lastAloud!!
        assertTrue(said, said.contains("2,000") && said.contains("rupees"))
        // Every sentence said: the last one too, never cut to "the rest is in the chat".
        assertTrue(said, said.contains("full figures are in the chat"))
        assertFalse(said, said.contains("The rest is in the chat"))
        assertTrue(SundayReview.words(said) <= 300)
        assertNull("no notification when it was said", Background.notifications(context).getNotification(IraSundayReview.NOTIFY_ID))
    }

    // ---- Sundays only ----

    @Test fun itIsNeverMadeOnAnotherDay() = runBlocking {
        Automations.set(Automations.Auto.WEEK, true)
        for (d in 0L..5L) {
            Background.at(mon.plusDays(d), 18, 0)
            assertFalse(mon.plusDays(d).dayOfWeek.name, IraSundayReview.sunday(context))
        }
        assertEquals(0, reviews())
        assertNull(SecurePrefs.getString(IraSundayReview.ONCE_KEY))
    }

    @Test fun onSundayItIsMadeOnceUnderItsSwitch() = runBlocking {
        assumeTrue(com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD)
        Background.at(sun, 18, 0)
        Automations.set(Automations.Auto.WEEK, false)
        assertFalse("its switch off: nothing", IraSundayReview.sunday(context))
        assertEquals(0, reviews())
        Automations.set(Automations.Auto.WEEK, true)
        assertTrue(IraSundayReview.sunday(context))
        assertEquals(1, reviews())
        assertEquals(sun.toString(), SecurePrefs.getString(IraSundayReview.ONCE_KEY))
        // A retried or repeated run says nothing twice.
        assertTrue(IraSundayReview.sunday(context))
        assertEquals(1, reviews())
        // The week reviewed: 13 to 19 Oct, both books in it (paper and live, each its own part).
        val t = IraHub.state.value.messages.last { it.text.startsWith("Boss, your Sunday review") }.text
        assertTrue(t, t.startsWith("Boss, your Sunday review for the week of 13 Oct"))
        assertTrue(t, t.contains("Paper:") && t.contains("Live:"))
    }

    /**
     * The Sunday 18:00 alarm (the arms' week's own, which runs the review now): armed for Sunday only; fired late on a
     * Saturday it only re-arms; fired on Sunday it starts the report worker and nothing else - the position watch's alarm
     * and its heartbeat stay exactly as they were, and no service is started or stopped (OPEN POSITIONS COME FIRST).
     */
    @Test fun theSundayAlarmRunsTheReviewAndLeavesThePositionWatchAlone() {
        assumeTrue(com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD)
        Background.workManager(context)
        Automations.set(Automations.Auto.WEEK, true)
        Background.at(sat, 10, 0)
        DailyReports.schedule(context, DailyReports.Kind.WEEK)
        assertEquals(at(sun, LocalTime.of(18, 0)), alarms(DailyReports.Kind.WEEK.action).single().triggerAtMs)
        Jobs.schedule(context, Jobs.Kind.LIVE)
        Heartbeat.schedule(context)
        fun watch() = (alarms("ol.job.LIVE") + alarms(Heartbeat.ACTION)).map { shadowOf(it.operation).savedIntent.action to it.triggerAtMs }
        val before = watch()
        assertEquals("the watch is armed", 1, alarms("ol.job.LIVE").size)
        val app = context.applicationContext as Application
        while (shadowOf(app).nextStartedService != null) Unit

        // A late alarm on Saturday: re-armed for Sunday, nothing made.
        DailyReports.fired(context, DailyReports.Kind.WEEK)
        assertTrue(Background.unique(context, "report.${DailyReports.Kind.WEEK.name}").isEmpty())

        Background.at(sun, 18, 0)
        DailyReports.fired(context, DailyReports.Kind.WEEK)
        assertEquals(at(sun.plusDays(7), LocalTime.of(18, 0)), alarms(DailyReports.Kind.WEEK.action).single().triggerAtMs)
        assertEquals(1, Background.unique(context, "report.${DailyReports.Kind.WEEK.name}").size)
        Background.await("the Sunday review from the report worker") { reviews() == 1 }
        assertEquals("the position watch's alarms untouched", before, watch())
        assertNull("no service started", shadowOf(app).nextStartedService)
        assertNull("no service stopped", shadowOf(app).nextStoppedService)
    }

    // ---- 21 Oct, enhancement round 3 (idea 7): the catch-up at the next unlocked moment that Sunday evening ----

    private fun catchUp() = runBlocking { IraSundayReview.catchUp(context) }

    @Test fun withoutJarvisOrItsSwitchTheCatchUpDoesNothing() {
        Background.at(sun, 19, 0)
        Automations.set(Automations.Auto.WEEK, false)
        assertNull(catchUp())
        assertEquals(0, reviews())
        assertNull(SecurePrefs.getString(IraSundayReview.SPOKEN_KEY))
    }

    @Test fun aLockedPhoneHearsNothingAndTheCatchUpStaysDue() {
        assumeTrue(com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD)
        Automations.set(Automations.Auto.WEEK, true)
        Background.at(sun, 19, 0)
        km.setKeyguardLocked(true)
        assertNull(catchUp())
        assertNull(JarvisSpeaker.lastAloud)
        assertEquals("nothing made on a locked phone", 0, reviews())
        assertNull(SecurePrefs.getString(IraSundayReview.SPOKEN_KEY))
    }

    @Test fun thePhoneOffAt18TheReviewIsMadeAndSaidAtTheNextUnlockOnce() {
        assumeTrue(com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD)
        Automations.set(Automations.Auto.WEEK, true)
        JarvisVoice.quietHours = false
        try {
            Background.at(sun, 19, 40)                            // the 18:00 alarm never ran (the phone was off)
            assertEquals(IraSundayReview.Delivered.SPOKEN, catchUp())
            assertNotNull(JarvisSpeaker.lastAloud)
            assertEquals("made once, kept in the chat", 1, reviews())
            assertEquals(sun.toString(), SecurePrefs.getString(IraSundayReview.ONCE_KEY))
            assertEquals(sun.toString(), SecurePrefs.getString(IraSundayReview.SPOKEN_KEY))
            JarvisSpeaker.lastAloud = null
            assertNull("never twice", catchUp())
            assertNull(JarvisSpeaker.lastAloud)
            assertEquals(1, reviews())
            // And the alarm running late after that says nothing again.
            assertTrue(runBlocking { IraSundayReview.sunday(context) })
            assertEquals(1, reviews())
        } finally { JarvisVoice.quietHours = true }
    }

    @Test fun lockedAt18ItIsSaidAtTheNextUnlockAndTheReadyNoticeGoes() {
        assumeTrue(com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD)
        Automations.set(Automations.Auto.WEEK, true)
        JarvisVoice.quietHours = false
        try {
            Background.at(sun, 18, 0)
            km.setKeyguardLocked(true)
            assertTrue(runBlocking { IraSundayReview.sunday(context) })
            assertNotNull(Background.notifications(context).getNotification(IraSundayReview.NOTIFY_ID))
            assertNull(JarvisSpeaker.lastAloud)
            Background.at(sun, 20, 15)
            km.setKeyguardLocked(false)
            assertEquals(IraSundayReview.Delivered.SPOKEN, catchUp())
            assertEquals("not made twice", 1, reviews())
            assertNull("the ready notice gone", Background.notifications(context).getNotification(IraSundayReview.NOTIFY_ID))
        } finally { JarvisVoice.quietHours = true }
    }

    @Test fun neverBefore18OrOnMondayOrAfterBossAskedForIt() {
        assumeTrue(com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD)
        Automations.set(Automations.Auto.WEEK, true)
        JarvisVoice.quietHours = false
        try {
            Background.at(sun, 17, 59)
            assertNull(catchUp())
            Background.at(sun.plusDays(1), 0, 5)
            assertNull("Monday: missed for good", catchUp())
            Background.at(sun, 19, 0)
            runBlocking { IraSundayReview.answer(locked = false) }    // Boss asked for it on the unlocked phone
            assertNull("asked already: not said again", catchUp())
            assertNull(JarvisSpeaker.lastAloud)
        } finally { JarvisVoice.quietHours = true }
    }
}
