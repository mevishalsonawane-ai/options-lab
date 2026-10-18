package com.optionslab.app.data

import com.optionslab.app.testing.RobolectricTest
import com.optionslab.app.work.Alerts
import com.optionslab.app.work.Notifier
import com.optionslab.engine.orb.ArmsCleanup
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Boss's diagnostics of 16 Oct 13:53 (build 29f5a5f), the smaller items: where a screen freeze was stuck now names who asked
 * (item 5), Jarvis's words round runs in MCX's evening while something is held there (item 1), an arm's own description is not
 * an error and an alerts-only Pine signal is not one either (item 6), a hidden arm is never armed while the cleanup's file
 * cannot be read (item 6), and the position books are read side by side at the restart, the slowest one named (item 2).
 */
class Diag1016Test : RobolectricTest() {
    @After fun down() { HiddenArms.resetForTest(context); Alerts.forgetPosted() }

    private fun frame(cls: String, m: String) = StackTraceElement(cls, m, null, -1)

    @Test fun aFreezeNamesTheCodeThatAskedNotOnlyTheVault() {
        val st = arrayOf(
            frame("android.os.BinderProxy", "transactNative"),
            frame("android.security.KeyStore2", "encrypt"),
            frame("com.optionslab.app.security.Vault", "encrypt"),
            frame("com.optionslab.app.security.Vault", "writeFile"),
            frame("com.optionslab.app.security.SecurePrefs", "put"),
            frame("com.optionslab.app.ui.AppModel\$save\$1", "invoke"),
            frame("com.optionslab.app.MainActivity", "onCreate"),
        )
        assertEquals("BinderProxy.transactNative in Vault.encrypt from SecurePrefs.put < AppModel.invoke", Speed.where(st))
        // The app's own frame on top: as before.
        assertEquals("Paper.place", Speed.where(arrayOf(frame("com.optionslab.app.data.Paper", "place"))))
        // No app frame at all, or an empty stack.
        assertEquals("Looper.loop", Speed.where(arrayOf(frame("android.os.Looper", "loop"))))
        assertEquals("?", Speed.where(emptyArray()))
        // A caller that is not storage: named straight after the app's first frame.
        assertEquals("Cipher.createCipher in BiometricGate.gatedCipher from BiometricGate.authenticate", Speed.where(arrayOf(
            frame("javax.crypto.Cipher", "createCipher"), frame("com.optionslab.app.security.BiometricGate", "gatedCipher"),
            frame("com.optionslab.app.security.BiometricGate", "authenticate"))))
    }

    @Test fun jarvissWordsRoundRunsInMcxsEveningWhileSomethingIsHeldThere() {
        assertTrue(com.optionslab.app.work.Tasks.wordsDue(nseSession = true) { false })
        assertTrue(com.optionslab.app.work.Tasks.wordsDue(nseSession = false) { true })
        assertFalse(com.optionslab.app.work.Tasks.wordsDue(nseSession = false) { false })
        // In NSE's session the MCX question is not even asked (no read for it).
        var asked = false
        com.optionslab.app.work.Tasks.wordsDue(nseSession = true) { asked = true; true }
        assertFalse(asked)
    }

    @Test fun anArmsOwnDescriptionIsNotAnErrorButARealFailureStillIs() {
        val sweep = "ORB Sweep armed on paper (its live trade at Zerodha follows only by its own \"Trade live\" switch, in Live with AI trades on), fully automatic: it fades a failed break of the opening range"
        assertEquals(Alerts.Kind.SUCCESS, Alerts.classify(sweep))
        assertEquals(Alerts.Kind.SUCCESS, Alerts.classify("Liquidity 15+5 armed: it sells at the next level, when the break fails, or at 15:10."))
        assertEquals(Alerts.Kind.ERROR, Alerts.classify("Order failed: margin"))
        assertEquals(Alerts.Kind.ERROR, Alerts.classify("The login failed"))
        assertEquals(Alerts.Kind.ERROR, Alerts.classify("Could not read the account"))
    }

    @Test fun anAlertsOnlyPineSignalIsInformationNotAnError() {
        Alerts.forgetPosted()
        Notifier.post(context, 5001, Notifier.RISK, "Jarvis: breakout above the last 20 candles' high FINNIFTY 5m: FLAT",
            "FINNIFTY 5m at 23,100.00 · Pine signal (alerts only, no order placed)", "pine", banner = Alerts.Kind.INFO)
        assertEquals(Alerts.Kind.INFO, Alerts.posted.last().kind)
        // A real risk notice keeps its red.
        Notifier.post(context, 2040, Notifier.RISK, "Daily loss limit reached", "Today's Paper P&L reached the limit.", "almanac")
        assertEquals(Alerts.Kind.ERROR, Alerts.posted.last().kind)
    }

    @Test fun aHiddenArmIsNeverArmedWhileTheCleanupsFileCannotBeRead() {
        HiddenArms.resetForTest(context)
        File(context.filesDir, "hidden_arms.vault").writeBytes(ByteArray(40) { 7 })
        HiddenArms.loadForTest()
        assertTrue(HiddenArms.unread)
        assertFalse(HiddenArms.mayArm(ArmsCleanup.ORB_SWEEP))
        assertTrue(HiddenArms.refusal("ORB Sweep").startsWith("ORB Sweep can't be switched on right now: the hidden-arms list could not be read yet"))
        // An arm the cleanup never manages is not held back.
        assertTrue(HiddenArms.mayArm("some other arm"))
        // Readable again (here: gone): back to its own state.
        File(context.filesDir, "hidden_arms.vault").delete()
        HiddenArms.reloadForTest(); HiddenArms.loadForTest()
        assertFalse(HiddenArms.unread)
        assertTrue(HiddenArms.mayArm(ArmsCleanup.ORB_SWEEP))
        assertEquals(ArmsCleanup.refusal("ORB Sweep"), HiddenArms.refusal("ORB Sweep"))
    }

    @Test fun thePositionBooksAreReadSideBySideAndTheSlowestIsNamed() = runBlocking {
        PositionsFirst.resetForTest(started = true)
        val books = (1..8).map { i -> "book $i" to (suspend { delay(300); i == 5 }) }
        val t0 = System.currentTimeMillis()
        assertTrue(PositionsFirst.readAll(books))
        val took = System.currentTimeMillis() - t0
        // Eight books of 300 ms each, four at a time: about 600 ms, not 2.4 s one after another.
        assertTrue("took $took ms", took < 1_800)
        assertTrue(PositionsFirst.slowest.orEmpty(), PositionsFirst.slowest.orEmpty().matches(Regex("book \\d \\d+ ms")))
        // Nothing open anywhere: false; one book that cannot be read: true (the watch decides).
        assertFalse(PositionsFirst.readAll(listOf("a" to (suspend { false }), "b" to (suspend { false }))))
        val unreadable: suspend () -> Boolean = { error("unreadable") }
        assertTrue(PositionsFirst.readAll(listOf("a" to (suspend { false }), "b" to unreadable)))
        PositionsFirst.resetForTest(started = false)
    }
}
