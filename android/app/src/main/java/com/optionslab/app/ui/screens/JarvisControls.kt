package com.optionslab.app.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.optionslab.app.ira.JarvisVoice
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type

/**
 * Jarvis's controls on the Ira page (Boss, 7 Oct: "only symbols, no words, all in a line in small size"): the mic (hold to
 * talk), mute and the chat, each an icon in a 48dp touch target, in one row - on the globe and in the chat's header.
 */
internal object JarvisControls {
    const val MIC = "Hold to talk to Jarvis"
    const val MUTE = "Mute Jarvis"
    const val UNMUTE = "Unmute Jarvis"
    const val OPEN_CHAT = "Open chat"
    const val CLOSE_CHAT = "Close chat"
    /** The only words the row adds: a quick tap on the mic says how it works. */
    const val HOLD_HINT = "Hold to talk"
    /** The microphone just allowed: said under the row (no turn is started). */
    const val MIC_ALLOWED = "Microphone allowed - hold to talk"
    /** A press shorter than this is a tap, not a hold. */
    const val QUICK_TAP_MS = 300L
    /** How long [HOLD_HINT] stays. */
    const val HINT_MS = 2_000L

    private fun icon(name: String, path: String): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black))
            .build()

    // The Material symbols' own outlines (the project has material-icons-core only, which has none of these).
    val Mic: ImageVector by lazy { icon("Mic", "M12,14c1.66,0 2.99,-1.34 2.99,-3L15,5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v6c0,1.66 1.34,3 3,3zM17.3,11c0,3 -2.54,5.1 -5.3,5.1S6.7,14 6.7,11H5c0,3.41 2.72,6.23 6,6.72V21h2v-3.28c3.28,-0.48 6,-3.3 6,-6.72h-1.7z") }
    val VolumeUp: ImageVector by lazy { icon("VolumeUp", "M3,9v6h4l5,5V4L7,9H3zM16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v8.05c1.48,-0.73 2.5,-2.25 2.5,-4.02zM14,3.23v2.06c2.89,0.86 5,3.54 5,6.71s-2.11,5.85 -5,6.71v2.06c4.01,-0.91 7,-4.49 7,-8.77s-2.99,-7.86 -7,-8.77z") }
    val VolumeOff: ImageVector by lazy { icon("VolumeOff", "M16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v2.21l2.45,2.45c0.03,-0.2 0.05,-0.41 0.05,-0.63zM19,12c0,0.94 -0.2,1.82 -0.54,2.64l1.51,1.51C20.63,14.91 21,13.5 21,12c0,-4.28 -2.99,-7.86 -7,-8.77v2.06c2.89,0.86 5,3.54 5,6.71zM4.27,3L3,4.27 7.73,9H3v6h4l5,5v-6.73l4.25,4.25c-0.67,0.52 -1.42,0.93 -2.25,1.18v2.06c1.38,-0.31 2.63,-0.95 3.69,-1.81L19.73,21 21,19.73l-9,-9L4.27,3zM12,4L9.91,6.09 12,8.18V4z") }
    val Chat: ImageVector by lazy { icon("ChatBubble", "M20,2H4c-1.1,0 -2,0.9 -2,2v18l4,-4h14c1.1,0 2,-0.9 2,-2V4c0,-1.1 -0.9,-2 -2,-2z") }
}

/** The mic's way to Jarvis's ears: the voice service's hold to talk ([JarvisVoice.holdTalk]); tests put a fake in [talkPathForTest]. */
internal interface TalkPath {
    /** Starts listening; false when it did not ([note]: what to tell Boss; [askMic]: asks Android for the microphone). */
    fun start(ctx: Context, askMic: () -> Unit, note: (String) -> Unit): Boolean
    /** The mic let go: [send] answers what was heard (nothing heard: nothing is sent); false drops it. */
    fun end(send: Boolean)
}

internal object VoiceTalkPath : TalkPath {
    override fun start(ctx: Context, askMic: () -> Unit, note: (String) -> Unit): Boolean = when {
        !JarvisVoice.available(ctx) -> { note("This phone has no on-device speech recognizer (Android 12 or later needed)."); false }
        !JarvisVoice.permitted(ctx) -> { askMic(); false }
        !JarvisVoice.holdTalk(ctx) -> { note("Jarvis could not start listening; try again."); false }
        else -> true
    }
    override fun end(send: Boolean) = JarvisVoice.talkEnd(send)
}

/** Tests only: replaces the voice service under the mic (null: the real one). */
@Volatile internal var talkPathForTest: TalkPath? = null

/**
 * The row: [showMic] (not while "Don't listen" is on: nothing could hear it), mute, and the chat ([chatOpen]: the icon
 * closes it). A note from the mic (the hold hint, or why it could not listen) shows under the row.
 */
@Composable
internal fun JarvisControlRow(chatOpen: Boolean, onChat: () -> Unit, modifier: Modifier = Modifier, showMic: Boolean = true) {
    var note by remember { mutableStateOf<String?>(null) }
    // The hold hint is brief; a reason it could not listen stays until the next press.
    LaunchedEffect(note) { if (note == JarvisControls.HOLD_HINT || note == JarvisControls.MIC_ALLOWED) { kotlinx.coroutines.delay(JarvisControls.HINT_MS); note = null } }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showMic) HoldToTalkMic(onNote = { note = it })
            MuteIcon()
            ControlIcon(JarvisControls.Chat, if (chatOpen) JarvisControls.CLOSE_CHAT else JarvisControls.OPEN_CHAT, on = chatOpen, onClick = onChat)
        }
        note?.let { Text(it, style = Type.label.copy(color = Color(0xFFB8C0E8), fontSize = 11.sp)) }
    }
}

/** One icon: [on] filled with the palette's brass (listening, muted, the chat open), else a quiet circle. */
@Composable
private fun IconFace(image: ImageVector, on: Boolean) {
    val p = LocalPalette.current
    Box(Modifier.size(36.dp).background(if (on) p.brass else p.card, CircleShape), contentAlignment = Alignment.Center) {
        Icon(image, contentDescription = null, modifier = Modifier.size(22.dp), tint = if (on) p.onPrimary else p.ink)
    }
}

@Composable
private fun ControlIcon(image: ImageVector, description: String, on: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(48.dp).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center) { IconFace(image, on) }
}

/** Mute: the same switch as Settings → Jarvis (a speaker, crossed out while muted). */
@Composable
private fun MuteIcon() {
    var mutedNow by remember { mutableStateOf(JarvisVoice.muted) }
    com.optionslab.app.ui.PollWhileStarted { while (true) { mutedNow = JarvisVoice.muted; kotlinx.coroutines.delay(2_000) } }
    ControlIcon(if (mutedNow) JarvisControls.VolumeOff else JarvisControls.VolumeUp,
        if (mutedNow) JarvisControls.UNMUTE else JarvisControls.MUTE, on = mutedNow) {
        if (mutedNow) JarvisVoice.muted = false else JarvisVoice.muteBy(com.optionslab.ira.VoiceMute.By.SETTINGS)
        mutedNow = !mutedNow
    }
}

/**
 * Hold to talk (Boss, 7 Oct): pressed, Jarvis listens silently ([JarvisVoice.holdTalk]: the Talk checks, recognizer
 * and speech choice, no "Yes, Boss?"), a pause sending nothing; let go (or after 60 s), all that was heard is asked
 * once and Jarvis replies. A quick tap (under [JarvisControls.QUICK_TAP_MS]) only says "Hold to talk" - nothing starts,
 * Jarvis is not interrupted; a finger slid off
 * sends nothing. TalkBack, which cannot hold: a double tap starts, the next one stops and sends.
 */
@Composable
private fun HoldToTalkMic(onNote: (String?) -> Unit) {
    if (JarvisVoice.deaf) return
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val path = talkPathForTest ?: VoiceTalkPath
    val talking = remember { mutableStateOf(false) }
    val noteNow = rememberUpdatedState(onNote)
    val ask = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok ->
        // Allowed: a brief note only - no turn is started (the next hold talks).
        noteNow.value(if (ok) JarvisControls.MIC_ALLOWED else "Jarvis needs the microphone to hear you.")
    }
    val begin = rememberUpdatedState<() -> Boolean> {
        noteNow.value(null)
        path.start(ctx, { ask.launch(android.Manifest.permission.RECORD_AUDIO) }, { noteNow.value(it) }).also { talking.value = it }
    }
    val end = rememberUpdatedState<(Boolean) -> Unit> { send -> if (talking.value) { talking.value = false; path.end(send) } }
    // Held 60 s: asked as if let go (the voice service caps the hold the same way).
    LaunchedEffect(talking.value) { if (talking.value) { kotlinx.coroutines.delay(com.optionslab.ira.HoldTalk.CAP_MS); end.value(true) } }
    // Leaving the page mid-hold: nothing is sent.
    DisposableEffect(Unit) { onDispose { if (talking.value) { talking.value = false; path.end(false) } } }
    val on = talking.value
    Box(Modifier.size(48.dp)
        .semantics {
            role = Role.Button
            contentDescription = JarvisControls.MIC
            if (on) stateDescription = "Listening"
            onClick(label = if (on) "Stop and send" else "Start talking") { if (talking.value) end.value(true) else begin.value(); true }
        }
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                down.consume()
                // The hold begins only once the press has lasted [JarvisControls.QUICK_TAP_MS]: a quick tap only says "Hold to
                // talk" - Jarvis is not interrupted, his follow-up window stays open, nothing starts or stops.
                // (Wrapped: null is the timeout - still pressed; a null inside is the finger sliding off.)
                val early = withTimeoutOrNull(JarvisControls.QUICK_TAP_MS) { listOf(waitForUpOrCancellation()) }
                if (early != null) {
                    early[0]?.let { up -> up.consume(); noteNow.value(JarvisControls.HOLD_HINT) }
                    return@awaitEachGesture
                }
                val started = begin.value()
                // Null: the finger slid off the button (or the press was taken): nothing is sent.
                val up = waitForUpOrCancellation()
                if (!started) return@awaitEachGesture
                if (up == null) end.value(false) else { up.consume(); end.value(true) }
            }
        },
        contentAlignment = Alignment.Center) { IconFace(JarvisControls.Mic, on) }
}
