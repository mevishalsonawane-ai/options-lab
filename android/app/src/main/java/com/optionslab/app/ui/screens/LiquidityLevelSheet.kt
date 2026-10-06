package com.optionslab.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.optionslab.app.data.Alarms
import com.optionslab.app.data.PriceAlarm
import com.optionslab.app.ui.components.BrassButton
import com.optionslab.app.ui.components.TextButton
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import com.optionslab.engine.orb.LiquidityOverlay
import com.optionslab.ira.LevelAlarm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The app's own price alarms as a liquidity level's sheet uses them: the same store ([Alarms]), so the market watch's
 * minute check rings them with its usual notification ([com.optionslab.app.work.Jobs]). Nothing here orders anything.
 */
internal interface LevelAlarms {
    /** Every standing alarm (the Alarms page's list). */
    val standing: StateFlow<List<PriceAlarm>>
    /** Sets [a] unless one on the same symbol, price and direction already stands; true when it was set. */
    suspend fun add(a: PriceAlarm): Boolean
    suspend fun remove(id: Long)
}

/** The app's alarm store, the list kept in [standing] (the app model's own, which the Alarms page shows). */
internal class StoreLevelAlarms(override val standing: MutableStateFlow<List<PriceAlarm>>) : LevelAlarms {
    override suspend fun add(a: PriceAlarm): Boolean = withContext(Dispatchers.IO) {
        // Checked and written under the store's own lock: two quick taps never set it twice.
        val added = synchronized(Alarms) {
            val dup = LevelAlarm.duplicate(Alarms.all().map(::standingOf), a.symbol, a.level, a.above)
            if (!dup) Alarms.upsert(a)
            !dup
        }
        standing.value = Alarms.all().sortedBy { it.id }
        added
    }

    override suspend fun remove(id: Long) {
        withContext(Dispatchers.IO) {
            Alarms.remove(id)
            standing.value = Alarms.all().sortedBy { it.id }
        }
    }
}

/** A stored alarm as [LevelAlarm] reads it. */
internal fun standingOf(a: PriceAlarm) = LevelAlarm.Standing(a.id, a.symbol, a.above, a.level, a.note)

/** A drawn level as [LevelAlarm] reads it. */
internal fun levelOf(s: LiquidityOverlay.Shape) = LevelAlarm.Level(s.edge, s.side, s.kind == LiquidityOverlay.Kind.POOL, s.from, s.to, s.taken)

/** A level tapped on the layer: the level, the close that took it (null: not taken), and the last bar's close then. */
internal data class PickedLevel(val level: LevelAlarm.Level, val takenAt: LocalDateTime?, val lastClose: Double?)

/** [s] of [model] as the sheet needs it. */
internal fun pickedOf(s: LiquidityOverlay.Shape, model: LiquidityOverlay.Model) = PickedLevel(levelOf(s),
    if (s.taken) model.bars.getOrNull(s.to)?.start?.plusMinutes(model.minutes.toLong()) else null, model.bars.lastOrNull()?.close)

/**
 * A liquidity level's sheet: its price, kind and chart, how far it is from [last], whether it was taken today, a button
 * setting a price alarm at it ([alarms]; null: no alarms here, e.g. IraGoldAlgo - no button), and the alarms already set
 * on [underlying]'s levels, each with Remove. The alarm only notifies.
 */
@Composable
internal fun LevelSheet(underlying: String, picked: PickedLevel, minutes: Int, today: LocalDate, last: Double?,
                        alarms: LevelAlarms?, onClose: () -> Unit) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val none = remember { MutableStateFlow<List<PriceAlarm>>(emptyList()) }
    val all by (alarms?.standing ?: none).collectAsState(Dispatchers.Main.immediate)
    var saving by remember { mutableStateOf(false) }
    var said by remember { mutableStateOf<String?>(null) }
    val key = PriceAlarm.CHART + underlying
    val level = picked.level
    val standing = all.map(::standingOf)
    val mine = LevelAlarm.onLevels(standing, key)
    val above = last?.let { LevelAlarm.above(it, level.price) }
    val dup = above != null && LevelAlarm.duplicate(standing, key, level.price, above)
    com.optionslab.app.ui.components.AlertDialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(securePolicy = com.optionslab.app.security.Capture.policy),
        title = { Text("Liquidity level", style = Type.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).testTag("liquidity-level")) {
                LevelAlarm.sheet(level, minutes, last, picked.takenAt, today).forEach {
                    Text(keepNumbersWhole(it), style = Type.bodySmall.copy(color = p.ink), modifier = Modifier.padding(vertical = 2.dp))
                }
                if (alarms != null) {
                    val ready = above != null && level.price > 0
                    BrassButton("Alert me when price reaches it", Modifier.fillMaxWidth().padding(top = 10.dp),
                        enabled = ready && !dup && !saving) {
                        val up = above ?: return@BrassButton
                        saving = true
                        val a = PriceAlarm(System.currentTimeMillis(), key, up, level.price, note = LevelAlarm.note(level, minutes))
                        scope.launch(Dispatchers.Main.immediate) {
                            val ok = runCatching { alarms.add(a) }.getOrDefault(false)
                            said = if (ok) "Alert set: $underlying ${if (up) "rises" else "falls"} to ${LevelAlarm.price(level.price)}"
                                else "An alert at this level is already set."
                            saving = false
                        }
                    }
                    when {
                        said != null -> said
                        dup -> "An alert at this level is already set."
                        last == null -> "Reading the last price…"
                        else -> null
                    }?.let { Text(keepNumbersWhole(it), style = Type.bodySmall.copy(color = p.inkSoft), modifier = Modifier.padding(top = 6.dp)) }
                    Text("It only notifies: checked by the market watch every minute, on market days. Nothing is ordered.",
                        style = Type.italic.copy(color = p.inkFaint, fontSize = 12.sp), modifier = Modifier.padding(top = 6.dp))
                    if (mine.isNotEmpty()) {
                        Text("Alerts on $underlying's levels", style = Type.label.copy(color = p.ink, fontWeight = FontWeight.SemiBold),
                            modifier = Modifier.padding(top = 12.dp))
                        mine.forEach { s ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(keepNumbersWhole(LevelAlarm.line(s)), style = Type.bodySmall.copy(color = p.ink), modifier = Modifier.weight(1f))
                                TextButton({ scope.launch(Dispatchers.Main.immediate) { runCatching { alarms.remove(s.id) }; said = null } }) { Text("Remove") }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClose) { Text("Close") } },
    )
}
