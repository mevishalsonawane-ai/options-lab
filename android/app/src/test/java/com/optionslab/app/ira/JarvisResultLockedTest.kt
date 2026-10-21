package com.optionslab.app.ira

import com.optionslab.app.testing.RobolectricTest
import com.optionslab.ira.LockRule
import com.optionslab.ira.Overheard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 18 Oct, review: a confirmed order's result, and a typed question's reply, were said with the order and its prices even
 * when the phone locked while they were on their way. The lock is now read when the words are said.
 */
class JarvisResultLockedTest : RobolectricTest() {
    private val result = "Bought 30 BANKNIFTY25OCT55000CE at ₹212.50, filled."

    private fun <T> locked(body: () -> T): T {
        val km = org.robolectric.Shadows.shadowOf(context.getSystemService(android.app.KeyguardManager::class.java))
        km.setKeyguardLocked(true)
        try { return body() } finally { km.setKeyguardLocked(false) }
    }

    @Test fun anOrderResultIsSaidWholeOnAnUnlockedPhone() {
        IraHub.init(context); IraHub.awaitLoadedBlocking()
        val said = JarvisVoice.resultWords(result)
        assertTrue(said, said.contains("212"))
        assertFalse(JarvisVoice.lockedSafe())
    }

    @Test fun anOrderResultOnAPhoneThatLockedSaysOnlyThatItIsReady() {
        IraHub.init(context); IraHub.awaitLoadedBlocking()
        locked {
            val said = JarvisVoice.resultWords(result)
            assertEquals(LockRule.RESULT_LOCKED, said)
            assertFalse(said, Overheard.holdsAccount(said))
        }
        // Unlocked again: whole once more.
        assertTrue(JarvisVoice.resultWords(result).contains("212"))
    }

    @Test fun aTypedReplyIsNotSaidWithTheAccountOnALockedPhone() {
        assumeTrue(com.optionslab.app.BuildConfig.JARVIS)
        IraHub.init(context); IraHub.awaitLoadedBlocking()
        val pnl = "Your P&L today is +₹4,200 on 2 trades."
        locked { JarvisSpeaker.speak(context, pnl, 8) }
        assertEquals(LockRule.ANSWER_LOCKED, JarvisSpeaker.lastAloud)
        JarvisSpeaker.speak(context, pnl, 8)
        assertTrue(JarvisSpeaker.lastAloud!!, JarvisSpeaker.lastAloud!!.contains("4,200") || JarvisSpeaker.lastAloud!!.contains("4200"))
    }
}
