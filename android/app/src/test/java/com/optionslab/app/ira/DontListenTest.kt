package com.optionslab.app.ira

import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.ira.Command
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.Shadows.shadowOf

/**
 * "Don't listen" (Boss, 6 Oct): the globe's button and the Settings switch are one setting, kept on the phone; while it is
 * on nothing opens the microphone - a start, the Talk button, Android restarting the service - and a running service
 * stops. Only [JarvisVoice.listenAgain] (his tap) turns it off; the chat's "don't listen" only turns it on.
 */
class DontListenTest : RobolectricTest() {
    @Before fun up() { JarvisVoice.resetDeafForTest() }
    @After fun down() { JarvisVoice.listenAgain(context); JarvisVoice.wanted = false; JarvisVoice.resetDeafForTest() }

    @Test fun theToggleIsKeptAndOverridesListening() {
        JarvisVoice.wanted = true
        assertFalse(JarvisVoice.deaf)
        assertTrue(JarvisVoice.listenOn)
        JarvisVoice.dontListen(context)
        assertTrue(JarvisVoice.deaf)
        assertTrue(JarvisVoice.deafState.value)
        assertFalse(JarvisVoice.listenOn)
        // Kept on the phone (survives a restart), and listening as Boss set it is left alone for when he taps again.
        assertTrue(SecurePrefs.getBoolean("jarvis.voice.nolisten", false))
        assertTrue(JarvisVoice.wanted)
        // A backup never carries it, so a restore cannot switch the microphone back on.
        assertFalse(com.optionslab.ira.Upkeep.carried("jarvis.voice.nolisten"))
        JarvisVoice.listenAgain(context)
        assertFalse(JarvisVoice.deaf)
        assertFalse(JarvisVoice.deafState.value)
        assertFalse(SecurePrefs.getBoolean("jarvis.voice.nolisten", true))
        assertTrue(JarvisVoice.wanted && JarvisVoice.listenOn)
    }

    @Test fun nothingStartsTheMicrophoneWhileItIsOn() {
        shadowOf(context as android.app.Application).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        JarvisVoice.wanted = true
        JarvisVoice.dontListen(context)
        shadowOf(context as android.app.Application).clearStartedServices()
        JarvisVoice.start(context)
        JarvisVoice.resume(context)
        assertFalse(JarvisVoice.talk(context))
        assertNull(shadowOf(context as android.app.Application).nextStartedService)
        assertEquals(JarvisVoice.NOT_LISTENING, JarvisVoice.state.value.problem)
    }

    @Test fun aRunningOrRestartedServiceStops() {
        JarvisVoice.wanted = true
        JarvisVoice.dontListen(context)
        // Android restarting the sticky service (no intent), or the notification's Talk: it stops without listening.
        val svc = org.robolectric.Robolectric.buildService(JarvisVoice::class.java).create()
        svc.startCommand(0, 1)
        assertTrue(shadowOf(svc.get()).isStoppedBySelf)
        assertEquals(JarvisVoice.NOT_LISTENING, JarvisVoice.state.value.problem)
        assertEquals(JarvisVoice.Mode.OFF, JarvisVoice.state.value.mode)
        assertFalse(JarvisVoice.listeningNow())
        svc.destroy()
    }

    @Test fun theChatTurnsItOffButNeverOn() = runBlocking {
        JarvisVoice.wanted = true
        val (said, act) = IraActions.prepare(Command(Command.Kind.LISTEN_OFF))
        assertNull(act)                                              // done at once: it only lowers what is heard
        assertEquals(com.optionslab.ira.NoListen.OFF, said)
        assertTrue(JarvisVoice.deaf)
        assertEquals(com.optionslab.ira.NoListen.ALREADY, IraActions.prepare(Command(Command.Kind.LISTEN_OFF)).first)
        // No words switch it back on: nothing the chat or the voice understands is a "listen" command.
        for (q in listOf("listen again", "start listening", "suno", "mic on", "unmute")) {
            val k = com.optionslab.ira.Commands.parse(q)?.kind
            if (k != null) runCatching { IraActions.prepare(Command(k)) }
            assertTrue(q, JarvisVoice.deaf)
        }
    }
}
