package com.optionslab.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.optionslab.app.security.PinLock
import com.optionslab.app.ui.components.BrassButton
import com.optionslab.app.ui.components.Parchment
import com.optionslab.app.ui.components.BrandEmblem
import com.optionslab.app.ui.components.BrandLogo
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The seal. First run asks for a PIN twice; after that it asks for the PIN or
 * a biometric. The PIN is typed on this pad, never on the system keyboard, so
 * no keyboard app can learn, cache or suggest it.
 */
@Composable
fun LockScreen(
    setup: Boolean,
    biometricLabel: String?,
    onBiometric: () -> Unit,
    onPin: (CharArray) -> PinLock.Result,
    onCreate: (CharArray) -> String?,
    notice: String?,
    calm: Boolean,
) {
    val p = LocalPalette.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var entered by remember { mutableStateOf("") }
    var first by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var broken by remember { mutableStateOf(false) }
    val shake = remember { Animatable(0f) }

    LaunchedEffect(Unit) { if (!setup && biometricLabel != null) { delay(350); onBiometric() } }

    // A live countdown while locked out.
    var lockout by remember { mutableStateOf(PinLock.lockoutSecondsLeft()) }
    LaunchedEffect(lockout) { if (lockout > 0) { delay(1000); lockout = PinLock.lockoutSecondsLeft() } }

    suspend fun reject(text: String) {
        message = text
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        for (x in listOf(10f, -8f, 5f, -2f, 0f)) shake.animateTo(x, tween(50))
        entered = ""
    }

    fun submit() {
        val pin = entered
        scope.launch {
            if (setup) {
                if (first == null) {
                    if (pin.length < PinLock.MIN_LENGTH) { reject("At least ${PinLock.MIN_LENGTH} digits."); return@launch }
                    first = pin; entered = ""; message = "Once more, to be sure."
                } else if (first != pin) {
                    first = null; reject("Those did not match. Start again.")
                } else {
                    val err = onCreate(pin.toCharArray())
                    if (err != null) { first = null; reject(err) } else broken = true
                }
                return@launch
            }
            when (val r = onPin(pin.toCharArray())) {
                PinLock.Result.Ok -> { broken = true; entered = "" }
                is PinLock.Result.Wrong -> reject(if (r.attemptsLeftBeforeLockout > 0) "Not the right PIN. ${r.attemptsLeftBeforeLockout} before a pause." else "Not the right PIN.")
                is PinLock.Result.LockedOut -> { lockout = r.secondsLeft; reject("Too many attempts. Wait ${r.secondsLeft} s.") }
                PinLock.Result.Wiped -> reject("Too many attempts: the vault has been erased.")
            }
        }
    }

    Parchment {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(48.dp))
            BrandLogo()
            Spacer(Modifier.height(36.dp))
            AnimatedContent(targetState = when {
                setup && first == null -> "Choose a PIN of ${PinLock.MIN_LENGTH} or more digits"
                setup -> "Confirm your PIN"
                else -> "Enter your PIN"
            }, transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(150)) }, label = "prompt") { t ->
                Text(t, style = Type.title.copy(color = p.ink, fontSize = 17.sp), textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.offset { IntOffset(shake.value.dp.roundToPx(), 0) }, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val shown = maxOf(PinLock.MIN_LENGTH, entered.length)
                repeat(shown) { i ->
                    val filled = i < entered.length
                    val s by animateFloatAsState(if (filled) 1f else 0.8f, tween(120), label = "dot")
                    Box(
                        Modifier.size(12.dp).scale(s)
                            .background(if (filled) p.ink else p.chip, CircleShape),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                when {
                    lockout > 0 -> "Paused for $lockout s after repeated attempts."
                    else -> message ?: notice ?: " "
                },
                style = Type.italic.copy(color = if (lockout > 0 || message?.startsWith("Not") == true) p.oxblood else p.inkSoft),
                textAlign = TextAlign.Center, modifier = Modifier.height(40.dp),
            )
            Spacer(Modifier.weight(1f))
            PinPad(
                enabled = lockout <= 0 && !broken,
                onDigit = { d -> if (entered.length < 12) { entered += d; message = null } },
                onDelete = { entered = entered.dropLast(1) },
                onEnter = { if (entered.isNotEmpty()) submit() },
            )
            Spacer(Modifier.height(14.dp))
            if (!setup && biometricLabel != null) {
                Row(
                    Modifier.clickable(onClick = onBiometric).padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Face, contentDescription = null, tint = p.ink)
                    Spacer(Modifier.width(8.dp))
                    Text(biometricLabel, style = Type.label.copy(color = p.ink, fontSize = 14.sp))
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PinPad(enabled: Boolean, onDigit: (Char) -> Unit, onDelete: () -> Unit, onEnter: () -> Unit) {
    val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "✓")
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        keys.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                row.forEach { k ->
                    PadKey(k, enabled) {
                        when (k) { "⌫" -> onDelete(); "✓" -> onEnter(); else -> onDigit(k[0]) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PadKey(label: String, enabled: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    val haptics = LocalHapticFeedback.current
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.95f else 1f, tween(90), label = "key")
    val action = label == "✓" || label == "⌫"
    Box(
        Modifier.size(72.dp).scale(s)
            .background(if (pressed) p.rule else if (label == "✓") p.brass else p.chip, CircleShape)
            .clickable(interactionSource = src, indication = null, enabled = enabled) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = Type.figureLarge.copy(fontSize = if (action) 22.sp else 26.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
            color = if (!enabled) p.inkFaint else if (label == "✓") p.onPrimary else p.ink))
    }
}

/** Shown instead of the app when the owner refuses compromised devices. */
@Composable
fun RefusedScreen(findings: List<String>, onQuit: () -> Unit) {
    val p = LocalPalette.current
    Parchment {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            BrandEmblem(96.dp, calm = true)
            Spacer(Modifier.height(20.dp))
            Text("IraAlgo will not open on this device", style = Type.title.copy(color = p.oxblood), textAlign = TextAlign.Center)
            Spacer(Modifier.height(10.dp))
            Text("This device shows signs of compromise, and you chose to refuse such devices:", style = Type.body.copy(color = p.ink), textAlign = TextAlign.Center)
            Spacer(Modifier.height(10.dp))
            findings.forEach { Text("• $it", style = Type.bodySmall.copy(color = p.inkSoft)) }
            Spacer(Modifier.height(24.dp))
            BrassButton("Close", Modifier.fillMaxWidth(0.6f), onClick = onQuit)
        }
    }
}
