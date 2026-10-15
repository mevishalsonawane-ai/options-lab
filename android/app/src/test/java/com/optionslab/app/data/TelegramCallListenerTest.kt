package com.optionslab.app.data

import android.app.Notification
import android.app.Person
import android.content.Intent
import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.IST
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.Clock
import java.time.LocalDateTime

/**
 * The Signal tester's capture (15 Oct): the notification reader takes ONLY Telegram's notifications and ONLY allow-listed chats
 * (another app, another chat, a group summary, the reader switched off: nothing is stored), with each message's own time; a
 * re-posted notification adds only its new lines. The share target reads only plain text. None of the capture code has any
 * path to an order.
 */
class TelegramCallListenerTest : RobolectricTest() {
    private val tg = "org.telegram.messenger"
    private val sample = "#sensex 73100 CE\nAbove 280\nTarget 310-345-400+++"
    private lateinit var t0: LocalDateTime

    @Before fun up() {
        t0 = AutomationSupport.tradingDayAt(9, 28).toLocalDateTime().plusSeconds(4)
        Market.testClock = Clock.fixed(t0.atZone(IST).toInstant(), IST)
        SignalTesterHost.setOn(true)
        SignalTesterHost.allow("Sensex Kings")
        SignalTesterHost.setListen(context, true)
    }

    @After fun down() { Market.testClock = null; SignalTesterHost.resetForTest() }

    private fun ms(t: LocalDateTime) = t.atZone(IST).toInstant().toEpochMilli()

    private fun plain(title: String, text: String, summary: Boolean = false): Notification =
        Notification.Builder(context, "test").setContentTitle(title).setContentText(text).setGroup("g").setGroupSummary(summary).build()

    private fun waitFor(n: Int): Boolean {
        val t = System.currentTimeMillis()
        while (System.currentTimeMillis() - t < 5_000) { if (SignalTesterHost.view.value.recs.size >= n) return true; Thread.sleep(10) }
        return false
    }

    @Test fun onlyTelegramAndOnlyAllowListedChatsAreRead() {
        TelegramCallListener.read("com.whatsapp", plain("Sensex Kings", sample), ms(t0))
        TelegramCallListener.read(tg, plain("Family group", sample), ms(t0))
        TelegramCallListener.read(tg, plain("Sensex Kings", sample, summary = true), ms(t0))
        TelegramCallListener.read(tg, null, ms(t0))
        Thread.sleep(200)
        assertTrue(SignalTesterHost.view.value.recs.isEmpty())
        // The allow-listed channel: recorded at its post time, to the second.
        TelegramCallListener.read(tg, plain("Sensex Kings", sample), ms(t0))
        assertTrue(waitFor(1))
        val r = SignalTesterHost.view.value.recs.single()
        assertEquals("Sensex Kings", r.channel); assertEquals(t0, r.at); assertEquals(SignalTesterHost.NOTIFICATION, r.source)
        assertEquals(SignalTesterHost.CALL, r.kind)
        // Off: nothing more.
        SignalTesterHost.setListen(context, false)
        TelegramCallListener.read(tg, plain("Sensex Kings", "SL hit"), ms(t0.plusMinutes(5)))
        Thread.sleep(200)
        assertEquals(1, SignalTesterHost.view.value.recs.size)
    }

    @Test fun aMessagingStyleNotificationGivesEachNewLineWithItsOwnTime() {
        val me = Person.Builder().setName("me").build()
        val style = Notification.MessagingStyle(me).setConversationTitle("Sensex Kings")
            .addMessage("Good morning", ms(t0.minusMinutes(20)), null as Person?)
            .addMessage(sample, ms(t0), null as Person?)
        val n = Notification.Builder(context, "test").setContentTitle("Sensex Kings").setStyle(style).build()
        val lines = TelegramCallListener.messages(n.extras, 1L)
        assertEquals(listOf("Good morning" to ms(t0.minusMinutes(20)), sample to ms(t0)), lines)
        TelegramCallListener.read(tg, n, ms(t0))
        assertTrue(waitFor(2))
        // Re-posted with one more line: only the new one is added.
        val style2 = Notification.MessagingStyle(me).setConversationTitle("Sensex Kings")
            .addMessage("Good morning", ms(t0.minusMinutes(20)), null as Person?).addMessage(sample, ms(t0), null as Person?)
            .addMessage("T1 done ✅", ms(t0.plusMinutes(13)), null as Person?)
        TelegramCallListener.read(tg, Notification.Builder(context, "test").setContentTitle("Sensex Kings").setStyle(style2).build(), ms(t0.plusMinutes(13)))
        assertTrue(waitFor(3))
        Thread.sleep(200)
        val recs = SignalTesterHost.view.value.recs
        assertEquals(3, recs.size)
        assertEquals(listOf(SignalTesterHost.NOT, SignalTesterHost.CALL, SignalTesterHost.FOLLOW), recs.map { it.kind })
        assertNull(recs[0].text)
        // A plain notification without text gives nothing.
        assertTrue(TelegramCallListener.messages(Notification.Builder(context, "test").setContentTitle("x").build().extras, 1L).isEmpty())
    }

    @Test fun theShareTargetReadsPlainTextOnly() {
        assertEquals(sample, SignalShareActivity.sharedText(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "  $sample ")))
        assertNull(SignalShareActivity.sharedText(Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_TEXT, "x")))
        assertNull(SignalShareActivity.sharedText(Intent(Intent.ACTION_VIEW).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "x")))
        assertNull(SignalShareActivity.sharedText(Intent(Intent.ACTION_SEND).setType("text/plain")))
        assertNull(SignalShareActivity.sharedText(null))
    }

    @Test fun theCaptureCodeHasNoPathToAnOrder() {
        val dir = listOf(File("src/main/java/com/optionslab/app/data"), File("app/src/main/java/com/optionslab/app/data")).first { it.exists() }
        for (name in listOf("SignalTesterHost.kt", "TelegramCallListener.kt", "SignalShareActivity.kt")) {
            val src = File(dir, name).readText()
            for (bad in listOf("Paper.place", "Paper.buy", "Paper.sell", "Broker.place", "placeOrder", "Kite", "OrbArms.", "Strategies.", "PineAuto.", "Orders.")) {
                assertFalse("$name mentions $bad", src.contains(bad))
            }
        }
    }
}
