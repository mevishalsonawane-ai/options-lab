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
}
