package com.optionslab.app.ui.screens

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.optionslab.app.data.KiteStream
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import com.optionslab.ira.LiveQuote
import kotlinx.coroutines.delay

/**
 * The small word beside live prices (round 2, Boss: "everything live"): "Prices: live" while Zerodha's stream brought a
 * price in the last 2 s, else "Prices: delayed 4s" (or "delayed" with no stream). Reads only in-memory state flows (the
 * stream's status and its coalesced version, at most four changes a second) and a one-second clock: nothing on the main
 * thread beyond reading them.
 */
@Composable
internal fun PriceFreshness(modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val version by KiteStream.version.collectAsState()
    val status by KiteStream.status.collectAsState()
    var clock by remember { mutableStateOf(System.currentTimeMillis()) }
    com.optionslab.app.ui.PollWhileStarted { while (true) { clock = System.currentTimeMillis(); delay(1_000) } }
    val at = KiteStream.lastTickAt.takeIf { it > 0 && status == KiteStream.Status.LIVE }
    val word = LiveQuote.says(at, maxOf(clock, version))
    Text("Prices: $word", style = Type.bodySmall.copy(color = if (word == "live") p.verdigris else p.amber), modifier = modifier)
}
