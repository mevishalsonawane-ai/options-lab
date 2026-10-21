package com.optionslab.app.data

import com.optionslab.app.security.MainWrites
import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.security.Vault
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.app.work.Heartbeat
import com.optionslab.app.work.WatchWake
import com.optionslab.engine.Right
import com.optionslab.engine.mcx.McxContract
import com.optionslab.engine.mcx.McxEveRules
import com.optionslab.engine.sandbox.Quote
import com.optionslab.ira.OpenProblems
import com.optionslab.ira.SessionCoverage
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

/**
 * Boss's diagnostics of 17 Oct 01:07 (build 66bf4e0, Pixel 9, Android 17):
 *  1. "The screen froze 5.8 s in BinderProxy.transactNative in Vault.encrypt" with no caller: the freeze now names who called
 *     even outside the app's own frames, settings writes made on the screen's thread go to the background writer (the PIN,
 *     Live and the other must-be-on-disk settings still written at once), and any vault write left there is noted with its
 *     keys and caller;
 *  2. the MCX coverage problem lingered after MCX closed: cleared once the watch runs (or a check finds no gap), MCX's
 *     coverage and the watch's own rule are one reading, and the watch keeps the processor awake while it runs;
 *  3. the Telegram line said "not in this build · not logged in" unless the card had been opened.
 */
class Diag1017Test : RobolectricTest() {
    @Before fun up() { MainWrites.resetForTest(); ProblemsHost.resetForTest(); WatchWake.resetForTest() }

    @After fun down() {
        MainWrites.resetForTest()
        SecurePrefs.deferOnMain = true
        Market.testClock = null
        WatchWake.resetForTest()
        ProblemsHost.resetForTest()
    }

    private fun frame(cls: String, m: String) = StackTraceElement(cls, m, null, -1)

    // ---- 1. the freeze names its caller ---------------------------------------------------------------------------------------

    @Test fun aFreezeWhoseCallersAreAllOutsideTheAppStillNamesOne() {
        // The release build folds the settings write into a renamed lambda ("o.a"): it is named, not dropped.
        assertEquals("BinderProxy.transactNative in Vault.encrypt from a.invoke", Speed.where(arrayOf(
            frame("android.os.BinderProxy", "transactNative"), frame("android.security.KeyStore2", "encrypt"),
            frame("com.optionslab.app.security.Vault", "encrypt"), frame("o.a", "invoke"),
            frame("androidx.compose.foundation.ClickableNode", "onClick"), frame("android.os.Looper", "loop"))))
        // Only Android's own code below (a call posted to the screen's thread): the nearest framework frame is named.
        assertEquals("BinderProxy.transactNative in Vault.encrypt from <framework Handler.handleCallback>", Speed.where(arrayOf(
            frame("android.os.BinderProxy", "transactNative"), frame("com.optionslab.app.security.Vault", "encrypt"),
            frame("android.os.Handler", "handleCallback"), frame("android.os.Looper", "loop"), frame("java.lang.reflect.Method", "invoke"))))
        // Kotlin's and Java's frames are skipped for the first one that is not theirs.
        assertEquals("Vault.encrypt from b.run", Speed.where(arrayOf(frame("com.optionslab.app.security.Vault", "encrypt"),
            frame("kotlin.coroutines.jvm.internal.BaseContinuationImpl", "resumeWith"), frame("kotlinx.coroutines.DispatchedTask", "run"),
            frame("o.b", "run"), frame("android.os.Looper", "loop"))))
        // The app's own caller, as before (16 Oct).
        assertEquals("BinderProxy.transactNative in Vault.encrypt from SecurePrefs.put < AppModel.invoke", Speed.where(arrayOf(
            frame("android.os.BinderProxy", "transactNative"), frame("com.optionslab.app.security.Vault", "encrypt"),
            frame("com.optionslab.app.security.Vault", "writeFile"), frame("com.optionslab.app.security.SecurePrefs", "put"),
            frame("com.optionslab.app.ui.AppModel\$save\$1", "invoke"))))
        // Nothing below the app's frame, no app frame, an empty stack: as before.
        assertEquals("Paper.place", Speed.where(arrayOf(frame("com.optionslab.app.data.Paper", "place"))))
        assertEquals("Looper.loop", Speed.where(arrayOf(frame("android.os.Looper", "loop"))))
        assertEquals("?", Speed.where(emptyArray()))
    }

    @Test fun aVaultFreezeCarriesTheWriteTheGuardCaught() {
        val w = "SecurePrefs.put jarvis.auto.guard by AutomationsCard.invoke"
        assertEquals("BinderProxy.transactNative in Vault.encrypt · $w", Speed.withWriter("BinderProxy.transactNative in Vault.encrypt", w))
        // A freeze elsewhere is not given a vault write it did not make; none caught: as it was.
        assertEquals("Choreographer.doFrame in BrainSection.invoke", Speed.withWriter("Choreographer.doFrame in BrainSection.invoke", w))
        assertEquals("BinderProxy.transactNative in Vault.encrypt", Speed.withWriter("BinderProxy.transactNative in Vault.encrypt", null))
        assertEquals("? · $w", Speed.withWriter(null, w))
    }

    @Test fun theGuardKeepsTheKeysAndTheCallerNeverAValue() {
        assertEquals("Vault writes on the screen's thread: none", MainWrites.line())
        val st = arrayOf(frame("dalvik.system.VMStack", "getThreadStackTrace"), frame("java.lang.Thread", "getStackTrace"),
            frame("com.optionslab.app.security.MainWrites", "note"), frame("com.optionslab.app.security.SecurePrefs", "put"),
            frame("com.optionslab.app.ira.Automations", "set"), frame("o.c", "invoke"))
        val at = System.currentTimeMillis()
        MainWrites.record("SecurePrefs.put jarvis.auto.guard", st, at)
        assertEquals(1L, MainWrites.count())
        assertEquals("SecurePrefs.put from Automations.set", MainWrites.last?.caller)
        assertEquals("Vault writes on the screen's thread: 1 (last: SecurePrefs.put jarvis.auto.guard from SecurePrefs.put from Automations.set)", MainWrites.line())
        assertEquals("SecurePrefs.put jarvis.auto.guard by SecurePrefs.put from Automations.set", MainWrites.since(at - 1_000))
        // A freeze that began after it: not that write's.
        assertNull(MainWrites.since(at + 1))
        // Off the screen's thread nothing is noted (the guard runs the write as it is).
        assertFalse(MainWrites.onMain())
        assertEquals(7, MainWrites.guard({ "never built" }) { 7 })
        assertEquals(1L, MainWrites.count())
    }

    private val prefsFile get() = File(context.noBackupFilesDir, "prefs.vault")

    @Test fun aSettingsWriteOnTheScreensThreadIsReadableAtOnceAndWrittenByTheBackgroundWriter() {
        SecurePrefs.put("seed", 1)   // the file exists (written here, the test's thread not yet the "screen's")
        MainWrites.testMainThread = Thread.currentThread()
        val before = Vault.writeCount(prefsFile)
        // A switch tapped on the screen: readable at once, every read sees it.
        SecurePrefs.put("jarvis.auto.guard", true)
        SecurePrefs.putAll(mapOf("ui.theme" to "dark", "n.other" to false))
        assertTrue(SecurePrefs.getBoolean("jarvis.auto.guard", false))
        assertEquals("dark", SecurePrefs.getString("ui.theme"))
        assertFalse(SecurePrefs.getBoolean("n.other", true))
        // Written by the background writer (its thread is not the screen's): nothing was written on this thread.
        assertTrue(SecurePrefs.flush())
        assertEquals("no vault write on the screen's thread", 0L, MainWrites.count())
        assertTrue(Vault.writeCount(prefsFile) > before)
        // And on disk: a new process reads them back.
        SecurePrefs.reload()
        assertTrue(SecurePrefs.getBoolean("jarvis.auto.guard", false))
        assertEquals("dark", SecurePrefs.getString("ui.theme"))
    }

    @Test fun aMustBeOnDiskWriteOnTheScreensThreadIsStillWrittenThereAndNoted() {
        SecurePrefs.put("seed", 1)
        MainWrites.testMainThread = Thread.currentThread()
        for ((k, v) in listOf("pin.fails" to 2, "g.kill" to true, "k.mode" to "paper", "jarvis.trades.paper.v3" to true, "breaker.day" to "2026-10-17")) {
            val before = Vault.writeCount(prefsFile)
            SecurePrefs.put(k, v)
            assertEquals("$k written before put returns", before + 1, Vault.writeCount(prefsFile))
            assertEquals("SecurePrefs.put $k", MainWrites.last?.what)
            assertTrue(MainWrites.last!!.caller, MainWrites.last!!.caller.startsWith("SecurePrefs.put"))
        }
        assertEquals(5L, MainWrites.count())
        // Mixed with a must-be-on-disk key: the whole write is made at once, never half now and half later.
        val before = Vault.writeCount(prefsFile)
        SecurePrefs.putAll(mapOf("ui.theme" to "light", "k.allow" to false))
        assertEquals(before + 1, Vault.writeCount(prefsFile))
        assertTrue(MainWrites.last!!.what.startsWith("SecurePrefs.putAll "))
        // Moving is switched off: written at once, as before.
        SecurePrefs.deferOnMain = false
        val b2 = Vault.writeCount(prefsFile)
        SecurePrefs.put("ui.theme", "dark")
        assertEquals(b2 + 1, Vault.writeCount(prefsFile))
    }

    @Test fun offTheScreensThreadAWriteIsMadeAtOnceAndNotNoted() {
        MainWrites.testMainThread = Thread()   // some other thread is the screen's
        val before = Vault.writeCount(prefsFile)
        SecurePrefs.put("ui.theme", "dark")
        assertEquals(before + 1, Vault.writeCount(prefsFile))
        assertEquals(0L, MainWrites.count())
    }

    @Test fun theAiTradesGoLiveSwitchsWritesRunOneAtATimeInTheOrderTapped() {
        // Its writes must reach the disk and the last tap must win: one lane, in order, off the screen's thread.
        val seen = java.util.Collections.synchronizedList(ArrayList<Int>())
        val threads = java.util.Collections.synchronizedSet(HashSet<Thread>())
        val me = Thread.currentThread()
        runBlocking {
            val jobs = (1..50).map { i -> com.optionslab.app.ui.screens.AI_LIVE_WRITES.launch { threads += Thread.currentThread(); seen += i } }
            jobs.forEach { it.join() }
        }
        assertEquals((1..50).toList(), seen.toList())
        assertFalse(me in threads)
    }

    // ---- 2. MCX's coverage and the watch's own rule ----------------------------------------------------------------------------

    private fun at(day: String, time: String) {
        Market.testClock = java.time.Clock.fixed(LocalDate.parse(day).atTime(LocalTime.parse(time)).atZone(com.optionslab.engine.IST).toInstant(),
            com.optionslab.engine.IST)
    }

    private fun agree(why: String) {
        val c = McxMarket.coverage()
        assertEquals(why, c.needs, McxMarket.watchDue())
        assertEquals(why, c.needs, SessionCoverage.gaps(listOf(c), watchAlive = false, streamUp = null).isNotEmpty())
    }

    @Test fun mcxsCoverageAndTheWatchsOwnRuleAgreeInEveryState() = runBlocking {
        // A Thursday evening, MCX open to 23:30, nothing armed or held: no need, no gap.
        at("2026-10-08", "20:00")
        agree("open, nothing there"); assertFalse(McxMarket.watchDue())
        // An arm armed (nothing held): the watch is due and a stopped watch would be a gap.
        assertTrue(McxPaperArms.setArmed(McxEveRules.SOURCE, true).contains("paper only"))
        agree("open, armed"); assertTrue(McxMarket.watchDue())
        assertEquals(listOf(McxPaperArms.label(McxEveRules.SOURCE)), McxMarket.coverage().armed)
        // After the close (23:30): neither, armed or not.
        at("2026-10-08", "23:31")
        agree("closed, armed"); assertFalse(McxMarket.watchDue())
        // Dussehra: shut in the morning, open from 17:00.
        at("2026-10-20", "11:00")
        agree("holiday morning, armed"); assertFalse(McxMarket.watchDue())
        at("2026-10-20", "18:00")
        agree("holiday evening, armed"); assertTrue(McxMarket.watchDue())
        // In NSE's hours too, MCX's part answers for MCX alone.
        at("2026-10-08", "11:00")
        agree("NSE open, MCX open, armed"); assertTrue(McxMarket.watchDue())
        // Nothing armed, a paper MCX option held: due for what is held.
        McxPaperArms.setArmed(McxEveRules.SOURCE, false)
        at("2026-10-08", "20:00")
        agree("open, nothing armed or held"); assertFalse(McxMarket.watchDue())
        val option = McxContract(148600327L, 580470L, "CRUDEOIL26OCT8550CE", "CRUDEOIL", LocalDate.of(2026, 10, 15), 8550.0, Right.CE, 0.1, 100)
        val r = Paper.place(McxMarket.paperContract(option), "BUY", 1, "MARKET", "NRML", null, null, Quote(242.0))
        assertTrue(r.message, r.ok)
        agree("open, held"); assertTrue(McxMarket.watchDue())
        assertEquals(1, McxMarket.coverage().held)
        at("2026-10-08", "23:31")
        agree("closed, held"); assertFalse(McxMarket.watchDue())
    }

    @Test fun aCoverageGapToldIsClearedAsSoonAsTheWatchRuns() {
        val key = SessionCoverage.Gap(McxMarket.NAME, true, "").key
        assertEquals("coverage:MCX:watch", key)
        ProblemsHost.report(key, "Session coverage", OpenProblems.Severity.SERIOUS, "MCX is open with 2 armed, but the market watch is not running")
        assertTrue(ProblemsHost.open().any { it.key == key })
        // The watch's pulse: the gap is over.
        Heartbeat.watchRunning()
        assertFalse(ProblemsHost.open().any { it.key == key })
        // Nothing to clear: nothing changes.
        Heartbeat.watchRunning()
        assertTrue(ProblemsHost.open().none { it.key.startsWith("coverage:") })
    }

    @Test fun theWatchKeepsTheProcessorAwakeWhileItRunsAndLetsGoAtItsEnd() {
        assertFalse(WatchWake.held())
        WatchWake.hold(context)
        assertTrue(WatchWake.held())
        // Renewed by every pulse: one lock, not one per pulse (one release lets it go).
        WatchWake.hold(context)
        WatchWake.release()
        assertFalse(WatchWake.held())
        // Released twice, or never held: never throws.
        WatchWake.release()
        assertTrue(WatchWake.HOLD_MS in 60_000L..5 * 60_000L)
    }

    // ---- 3. the Telegram line --------------------------------------------------------------------------------------------------

    /** [f] on a thread that is not the screen's (the diagnostics are built on IO). */
    private fun <T> offMain(f: () -> T): T {
        var out: Result<T>? = null
        val t = Thread { out = runCatching(f) }
        t.start(); t.join(10_000)
        return out!!.getOrThrow()
    }

    @Test fun theTelegramLineSaysTheStoredLoginWithoutTheCardOpened() {
        SecurePrefs.put(TelegramCapture.K_LOGGED_IN, true)
        SecurePrefs.put(TelegramCapture.K_CAPTURE, true)
        SignalTesterHost.allow("Sensex Kings"); SignalTesterHost.allow("Other Calls"); SignalTesterHost.allow("Pasted Chat")
        // The card never opened: the view knows nothing; the line reads the settings itself.
        assertFalse(TelegramCapture.view.value.loggedIn)
        val line = offMain { TelegramCapture.diagLine() }
        assertTrue(line, "· logged in ·" in line)
        assertTrue(line, "· capture on " in line)
        assertTrue(line, line.endsWith("· the Signal tester follows 3 chats in all · stopped · 0 channels selected"))
        // The library is said as it is: loaded with this build's transport...
        TelegramCapture.resetForTest()
        TelegramCapture.testTransport = { error("not opened for the line") }
        assertTrue(offMain { TelegramCapture.diagLine() }.startsWith("Telegram login (Signal tester, read-only): in this build · logged in"))
    }

    @Test fun theLibrarysWordsSayWhatIsKnown() {
        assertEquals("in this build", TelegramCapture.libWords(gold = false, ok = true, failed = false, why = null, packaged = null))
        assertEquals("Not on this phone", TelegramCapture.libWords(false, ok = false, failed = true, why = "Not on this phone", packaged = true))
        assertEquals("not in this build", TelegramCapture.libWords(false, ok = false, failed = true, why = null, packaged = null))
        assertEquals("in this build (library not loaded yet)", TelegramCapture.libWords(false, ok = false, failed = false, why = null, packaged = true))
        assertEquals("not in this build", TelegramCapture.libWords(false, ok = false, failed = false, why = null, packaged = false))
        assertEquals("library not loaded yet", TelegramCapture.libWords(false, ok = false, failed = false, why = null, packaged = null))
        assertEquals("not in this build", TelegramCapture.libWords(gold = true, ok = true, failed = false, why = null, packaged = true))
    }

    @Test fun backToPaperFromTheScreenIsSeenAtOnce() {
        SecurePrefs.put(com.optionslab.app.ira.IraNewsTrades.PAPER_KEY, false)
        MainWrites.testMainThread = Thread.currentThread()
        com.optionslab.app.ira.IraNewsTrades.paperNow()
        assertTrue(com.optionslab.app.ira.IraNewsTrades.paperFirst)
        assertEquals("no Keystore write on the screen's thread", 0L, MainWrites.count())
        assertTrue(SecurePrefs.flush())
    }

    @Test fun onTheScreensThreadTheLineNeverLoadsTheLibraryOrSaysItIsMissing() {
        TelegramCapture.resetForTest()
        MainWrites.testMainThread = Thread.currentThread()
        val line = TelegramCapture.diagLine()
        assertTrue(line, line.startsWith("Telegram login (Signal tester, read-only): library not loaded yet ·"))
        assertFalse(line, "not in this build" in line)
    }
}
