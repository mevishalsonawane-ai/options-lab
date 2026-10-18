package com.optionslab.app.security

import com.optionslab.app.IraAlgoApp
import com.optionslab.app.MainActivity
import com.optionslab.app.testing.RobolectricTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.Robolectric

/**
 * 18 Oct, review: the settings still in memory (the screen's deferred writes, the lazily kept figures) are put on disk on a
 * background thread when the screen goes away and when Android asks for memory back, so a process killed after loses none.
 */
class FlushOnStopTest : RobolectricTest() {
    private fun awaitRuns(atLeast: Int) {
        val end = System.currentTimeMillis() + 10_000
        while (SecurePrefs.flushSoonRuns.get() < atLeast && System.currentTimeMillis() < end) Thread.sleep(10)
    }

    @Test fun aDeferredWriteReachesDiskAfterFlushSoon() {
        SecurePrefs.putAllSoon(mapOf("ui.theme" to "dark"))
        runBlocking { SecurePrefs.flushSoon().join() }
        assertTrue(SecurePrefs.reload())
        assertEquals("dark", SecurePrefs.getString("ui.theme"))
    }

    @Test fun theScreensStopFlushesInTheBackground() {
        // The screen hides other apps' overlays (as the app's own screen tests grant it).
        org.robolectric.Shadows.shadowOf(context as android.app.Application).grantPermissions(android.Manifest.permission.HIDE_OVERLAY_WINDOWS)
        val before = SecurePrefs.flushSoonRuns.get()
        val c = Robolectric.buildActivity(MainActivity::class.java).create().start()
        c.stop()
        awaitRuns(before + 1)
        assertTrue("onStop is wired to the background flush", SecurePrefs.flushSoonRuns.get() > before)
        c.destroy()
    }

    @Test fun aMemoryTrimFlushesInTheBackground() {
        val before = SecurePrefs.flushSoonRuns.get()
        IraAlgoApp().onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        awaitRuns(before + 1)
        assertTrue("onTrimMemory is wired to the background flush", SecurePrefs.flushSoonRuns.get() > before)
    }
}
