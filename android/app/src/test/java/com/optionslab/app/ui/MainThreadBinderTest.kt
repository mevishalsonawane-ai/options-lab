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
    private fun src(path: String): String? =
        listOf(File("src/main/java/$path"), File("app/src/main/java/$path")).firstOrNull { it.isFile }?.readText()

    @Test fun theModelNeverStartsTheWatchOnTheMainThread() {
        val s = src("com/optionslab/app/ui/AppModel.kt") ?: return
        assertFalse(Regex("withContext\\(Dispatchers\\.Main\\)\\s*\\{\\s*(runCatching\\s*\\{\\s*)?(com\\.optionslab\\.app\\.work\\.)?Jobs\\.").containsMatchIn(s))
        assertTrue("arming still starts the watch", s.contains("Jobs.ensureWatch(ctx)"))
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
}
