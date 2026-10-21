package com.optionslab.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 12 Oct: the screen stuck 3.9 s in BinderProxy.transactNative at 16:24 (Boss switching ORB, ORB Fresh and the SILVERM call)
 * and 3.8 s at 10:41 (a Jarvis strategy approved): the watch's start (a foreground-service start, a binder call into the
 * system) was hopped onto the main thread from the model, and the approval (Pine scripts' vault reads and writes, Keystore
 * binder calls) ran in the screen's scope on the main thread. Pure JVM: the sources say they no longer do.
 */
class MainThreadBinderTest {
    // 21 Oct, enhancement round 4: the market clock at noon IST on a trading day unless the test sets its own.
    @get:org.junit.Rule val marketClock = com.optionslab.app.testing.MarketClockRule()
    private fun src(path: String): String? =
        listOf(File("src/main/java/$path"), File("app/src/main/java/$path")).firstOrNull { it.isFile }?.readText()

    @Test fun theModelNeverStartsTheWatchOnTheMainThread() {
        val s = src("com/optionslab/app/ui/AppModel.kt") ?: return
        assertFalse(Regex("withContext\\(Dispatchers\\.Main\\)\\s*\\{\\s*(runCatching\\s*\\{\\s*)?(com\\.optionslab\\.app\\.work\\.)?Jobs\\.").containsMatchIn(s))
        assertTrue("arming still starts the watch", s.contains("Jobs.ensureWatch(ctx)"))
    }

    @Test fun theAiTradesGoLiveSwitchWritesOffTheMainThreadInOrder() {
        // 17 Oct (the screen froze 5.8 s in Vault.encrypt): its write, which must reach the disk, is made off the screen's thread.
        val s = src("com/optionslab/app/ui/screens/IraPage.kt") ?: return
        assertFalse(s.contains("aiLive = false; com.optionslab.app.ira.IraNewsTrades.paperFirst = true"))
        assertFalse(s.contains("aiLive = true; com.optionslab.app.ira.IraNewsTrades.paperFirst = false"))
        assertTrue(s.contains("AI_LIVE_WRITES.launch { runCatching { com.optionslab.app.ira.IraNewsTrades.paperFirst = v } }"))
    }

    @Test fun approvingAJarvisStrategyRunsOffTheMainThread() {
        val s = src("com/optionslab/app/ui/screens/IraPage.kt") ?: return
        assertFalse(s.contains("scope.launch { IraHub.approve("))
        assertTrue(s.contains("scope.launch(kotlinx.coroutines.Dispatchers.IO) { IraHub.approve("))
    }

    /** 18 Oct, review: the Replay page's harvested days (asset and folder listings) are read on the IO threads, not in composition. */
    @Test fun theReplayDaysAreReadOffTheMainThread() {
        val s = src("com/optionslab/app/ui/screens/PresetsReplay.kt") ?: return
        assertFalse(s.contains("daysFor = model::replayDays"))
        assertTrue(s.contains("withContext(kotlinx.coroutines.Dispatchers.IO) { UNDERLYINGS.associateWith { model.replayDays(it) } }"))
    }

    /** 18 Oct, review: the P&L calendar's and Pine's CSV exports write through a document provider's stream on the IO threads. */
    @Test fun csvExportsWriteOffTheMainThread() {
        for (f in listOf("PnlCalendarScreen.kt", "PineScreen.kt")) {
            val s = src("com/optionslab/app/ui/screens/$f") ?: continue
            assertFalse(f, s.contains("ctx.contentResolver.openOutputStream(uri)"))
            assertTrue(f, Regex("withContext\\(kotlinx\\.coroutines\\.Dispatchers\\.IO\\)\\s*\\{\\s*runCatching\\s*\\{\\s*resolver\\.openOutputStream\\(uri\\)").containsMatchIn(s))
        }
    }

    // 18 Oct, review: taps and the voice's main-thread callbacks did vault writes on the main thread.
    @Test fun theNoTapAndDismissTapSaveOffTheMainThread() {
        val page = src("com/optionslab/app/ui/screens/IraPage.kt") ?: return
        assertFalse("the No button cancels on the hub's thread", page.contains("IraHub.cancelAction("))
        assertTrue(page.contains("IraHub.cancelAsync(mineId, by = com.optionslab.ira.Requests.By.TAP)"))
        // Solo's card reads Solo's trades off the main thread, never in composition.
        assertFalse(page.contains("remember(on) { com.optionslab.app.ira.IraSolo.card() }"))
        assertFalse(page.contains("remember(on) { com.optionslab.app.ira.IraSolo.record() }"))
        val hub = src("com/optionslab/app/ira/IraHub.kt") ?: return
        assertTrue(hub.contains("fun cancelAsync(") && hub.contains("val c = claimCancel(id) ?: return null") && hub.contains("scope.launch(Dispatchers.IO) { finishCancel(c, said, by) }"))
        assertTrue("dismiss saves on the I/O thread", hub.contains("return scope.launch(Dispatchers.IO) { saveState() }"))
    }

    @Test fun theVoicesMainThreadMarkersAreWrittenInTheBackground() {
        val v = src("com/optionslab/app/ira/JarvisVoice.kt") ?: return
        for (k in listOf("SecurePrefs.put(TAP_BROKEN", "SecurePrefs.put(\"jarvis.voice.tapsilent\"", "SecurePrefs.put(\"jarvis.hearing.told\""))
            assertFalse(k, v.contains(k))
        assertTrue(v.contains("putAllSoon(mapOf(TAP_BROKEN to true))"))
        assertTrue(v.contains("putAllSoon(mapOf(\"jarvis.voice.tapsilent\" to v.toString()))"))
        assertTrue(v.contains("putAllSoon(mapOf(\"jarvis.hearing.told\" to day))"))
        // A spoken no is carried out off the main thread too.
        assertFalse(v.contains("else { IraHub.cancelAction("))
        // Jarvis's own yes-or-no questions are said only through the lock-aware path.
        assertFalse(Regex("sayWhenFree\\([^)]*\"question\"\\)").containsMatchIn(v))
    }

    /** 18 Oct (final review F7): the Hero dialog's live state is worked out on the IO threads, never in composition. */
    @Test fun theHeroDialogsLiveStateIsReadOffTheMainThread() {
        val s = src("com/optionslab/app/ui/screens/OrbRows.kt") ?: return
        assertFalse(s.contains("+ com.optionslab.app.data.ArmLive.stateOf("))
        assertTrue(Regex("produceState\\([^)]*\\)\\s*\\{\\s*value = kotlinx\\.coroutines\\.withContext\\(kotlinx\\.coroutines\\.Dispatchers\\.IO\\)\\s*\\{\\s*" +
            "com\\.optionslab\\.app\\.data\\.ArmLive\\.stateOf\\(").containsMatchIn(s))
        // ArmLive's state words and its paper-fill note read the Live switch from memory, never the settings.
        val a = src("com/optionslab/app/data/ArmLive.kt") ?: return
        assertTrue(a.contains("if (!liveFlags().first) return"))
        // Each function's whole body, up to its closing brace at the object's indent.
        for (f in listOf("fun stateOf(key: String): String {", "fun entered(ask: Ask) {", "internal fun doorRefusal(arm: String?): String? {")) {
            val at = a.indexOf(f)
            assertTrue(f, at >= 0)
            val body = a.substring(at, a.indexOf("\n    }", at))
            assertFalse(f, body.contains("AppSettings.load") || body.contains("SecurePrefs."))
        }
    }
}
