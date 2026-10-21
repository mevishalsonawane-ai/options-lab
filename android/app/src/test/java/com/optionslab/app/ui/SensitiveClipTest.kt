package com.optionslab.app.ui

import android.content.ClipDescription
import android.content.ClipboardManager
import com.optionslab.app.testing.RobolectricTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 21 Oct, enhancement round 3 (idea 6): the diagnostics go to the clipboard marked sensitive (Android 13+ hides their preview). */
class SensitiveClipTest : RobolectricTest() {
    private val cm get() = context.getSystemService(ClipboardManager::class.java)

    @Test fun aCopyIsMarkedSensitiveWithAndroidsOwnExtra() {
        assertEquals("Android's own name for it", ClipDescription.EXTRA_IS_SENSITIVE, SensitiveClip.EXTRA)
        assertEquals("android.content.extra.IS_SENSITIVE", SensitiveClip.EXTRA)
        assertTrue(SensitiveClip.copy(context, "IraAlgo diagnostics", "line one\nline two"))
        val clip = cm.primaryClip!!
        assertEquals("line one\nline two", clip.getItemAt(0).text.toString())
        assertEquals("IraAlgo diagnostics", clip.description.label.toString())
        assertTrue("marked sensitive", clip.description.extras!!.getBoolean(SensitiveClip.EXTRA))
    }

    @Test fun anEmptyTextIsCopiedMarkedToo() {
        assertTrue(SensitiveClip.copy(context, "IraAlgo diagnostics", ""))
        assertTrue(cm.primaryClip!!.description.extras!!.getBoolean(SensitiveClip.EXTRA))
    }
}
