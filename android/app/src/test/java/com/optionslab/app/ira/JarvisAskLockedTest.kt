package com.optionslab.app.ira

import com.optionslab.app.testing.RobolectricTest
import com.optionslab.ira.Overheard
import com.optionslab.ira.Requests
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 18 Oct, review: Jarvis's own yes-or-no question about a request (a stale trade's close, a rescue stop, a news trade)
 * named the held symbol and its prices aloud on a locked phone. Its words are now worked out at the moment they are said:
 * unlocked, the question whole; locked, only how many requests wait and "unlock to answer".
 */
class JarvisAskLockedTest : RobolectricTest() {
    private val ask = Requests.spoken("close BANKNIFTY25OCT55000CE",
        "Boss, BANKNIFTY25OCT55000CE has gone nowhere for 40 minutes (212.50 against the 230.00 you paid). Yes or no?")

    @Test fun anUnlockedPhoneHearsTheQuestionWhole() {
        IraHub.init(context); IraHub.awaitLoadedBlocking()
        assertEquals(ask, JarvisVoice.askWords(ask))
    }

    @Test fun aLockedPhoneHearsOnlyThatARequestWaits() {
        IraHub.init(context); IraHub.awaitLoadedBlocking()
        val km = org.robolectric.Shadows.shadowOf(context.getSystemService(android.app.KeyguardManager::class.java))
        km.setKeyguardLocked(true)
        try {
            val said = JarvisVoice.askWords(ask)
            // No request waits in the hub here: counted as one, never as none.
            assertEquals(Requests.lockedAsk(1), said)
            assertFalse(said, Overheard.holdsAccount(said))
            assertFalse(said, said.contains("BANKNIFTY") || said.contains("212") || said.contains("230"))
            // The re-ask after a held yes goes the same way.
            assertEquals(Requests.lockedAsk(1), JarvisVoice.askWords(com.optionslab.ira.AnswerWindow.hold(ask)))
        } finally { km.setKeyguardLocked(false) }
        // Unlocked again: the question whole once more.
        assertEquals(ask, JarvisVoice.askWords(ask))
    }
}
