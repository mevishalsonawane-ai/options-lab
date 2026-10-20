package com.optionslab.app.ira

import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.security.Vault
import com.optionslab.app.testing.FakeAndroidKeyStore
import com.optionslab.app.testing.RobolectricTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/**
 * 20 Oct, review: a tightening voice setting (Google speech off) must reach the disk even when its first write fails: the
 * retry queued nothing (putAllSoon of an empty map) and the looser value came back after a kill. It now writes the map itself.
 */
class JarvisVoiceKeepTest : RobolectricTest() {
    private fun onDisk(): Any? = Vault.readFile(File(context.noBackupFilesDir, "prefs.vault"))
        ?.let { JSONObject(String(it, Charsets.UTF_8)).opt("jarvis.voice.google") }

    @Test fun googleSpeechOffSurvivesAFailedFirstWrite() {
        JarvisVoice.googleSpeech = true
        SecurePrefs.flush()
        assertEquals(true, onDisk())
        // The next key read fails once (a passing Keystore fault): the background write of "off" fails, the retry must write it.
        FakeAndroidKeyStore.failNext.set(1)
        JarvisVoice.googleSpeech = false
        assertFalse(JarvisVoice.googleSpeech)
        val until = System.currentTimeMillis() + 10_000
        // The fault is spent by the background write first (nothing else reads the key meanwhile); only then is the file read.
        while (FakeAndroidKeyStore.failNext.get() > 0 && System.currentTimeMillis() < until) Thread.sleep(5)
        while (onDisk() != false && System.currentTimeMillis() < until) Thread.sleep(20)
        assertEquals("the tighter value is on disk", false, onDisk())
    }
}
