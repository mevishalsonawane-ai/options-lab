package com.optionslab.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.optionslab.app.data.OrbArms
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import com.optionslab.engine.orb.LiquidityRules
import com.optionslab.ira.LiquidityOpen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.util.Locale

/**
 * Liquidity 15+5's open position, live, under its row on the Strategies card ([LiquidityOpen]): the contract, size, entry,
 * premium and P&L, each exit as it stands with how far away it is, the best and worst premium so far and where the index
 * sits between the index stop and the target.
 *
 * Refreshed at once on the screen's own refresh of the arms (a new [open] or [mark]), and every [tickMs] (15 s) while the
 * position is open and the panel is on screen with the app in front ([com.optionslab.app.ui.PollWhileStarted]; it stops
 * when the panel leaves), so the countdowns and "As of" move on while the premium is flat. Each refresh reads [read]
 * ([OrbArms.liquidityOpenNow] in the app for this position's book and contract: the position, its mark and its own index's
 * last price, copied under the arms' lock) and works the figures out on Dispatchers.IO, and the state is written only
 * when they changed. Reads only: it places, closes and changes nothing. Never in the GOLD build.
 */
@Composable
internal fun LiquidityOpenPanel(
    open: OrbArms.Position, mark: Double?,
    read: (suspend (OrbArms.Position) -> OrbArms.LiquidityOpenNow?)? = null,
    now: () -> LocalDateTime = { com.optionslab.app.data.Market.now().toLocalDateTime() },
    tickMs: Long = 15_000,
) {
    if (com.optionslab.app.BuildConfig.GOLD) return
    var shown by remember { mutableStateOf<LiquidityOpen.View?>(null) }
    com.optionslab.app.ui.PollWhileStarted(open, mark) {
        while (true) {
            val v = withContext(Dispatchers.IO) {
                val r = read?.let { f -> try { f(open) } catch (e: CancellationException) { throw e } catch (e: Exception) { null } }
                runCatching { liquidityOpenView(open, mark, r, now()) }.getOrNull()
            }
            if (v != null && v != shown) shown = v
            // Flat (no position open): no ticking.
            if (!open.open) break
            delay(tickMs)
        }
    }
    shown?.let { LiquidityOpenContent(it) }
}

/**
 * The panel's figures for [open] at [now]: the fresher copy from [read] when it is the same position (with the arm's last
 * index price), else [open] and [mark] from the row with no index price. Pure.
 */
internal fun liquidityOpenView(open: OrbArms.Position, mark: Double?, read: OrbArms.LiquidityOpenNow?, now: LocalDateTime): LiquidityOpen.View {
    val same = read?.takeIf { it.position.arm == open.arm && it.position.symbol == open.symbol && it.position.entryTime == open.entryTime }
    val p = same?.position ?: open
    val book = LiquidityRules.BOOKS.firstOrNull { it.source == p.arm }
    val underlying = book?.let { LiquidityRules.underlyingOf(it) } ?: "BANKNIFTY"
    return LiquidityOpen.view(LiquidityOpen.Input(
        underlying = underlying, right = p.right, symbol = p.symbol, qty = p.qty, lots = p.lot?.let { p.lots }, live = p.live,
        entry = p.entry, entryTime = p.entryTime, ltp = same?.mark ?: mark, stopTrigger = p.stopTrigger, stopResting = p.stopOrderId != null,
        level = p.level, target = p.target, index = same?.index, indexAt = same?.indexAt,
        peak = p.peak, low = p.low, timed = p.timed, buyCharges = p.charges,
    ), now)
}

/** The panel from its figures: every line through [keepNumbersWhole]; the index's place between stop and target as a bar. */
@Composable
internal fun LiquidityOpenContent(v: LiquidityOpen.View) {
    val p = LocalPalette.current
    val soft = Type.bodySmall.copy(color = p.inkSoft, fontSize = 12.sp)
    val ink = Type.bodySmall.copy(color = p.ink, fontSize = 12.sp)
    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp).background(p.chip, RoundedCornerShape(12.dp)).padding(12.dp)) {
        Text(v.title, style = Type.label.copy(color = p.inkSoft, fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.9.sp))
        Text(keepNumbersWhole(v.contract), style = ink.copy(fontWeight = FontWeight.SemiBold))
        Text(keepNumbersWhole(v.entered), style = soft)
        Text(keepNumbersWhole(v.now), style = ink)
        val gross = v.gross
        val tone = when { gross == null -> p.inkSoft; gross >= 0 -> p.verdigris; else -> p.oxblood }
        Text(keepNumbersWhole(v.pnl), style = ink.copy(color = tone, fontWeight = FontWeight.SemiBold))
        v.afterCharges?.let { Text(keepNumbersWhole(it), style = soft) }
        Text("EXITS", style = Type.label.copy(color = p.inkSoft, fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.9.sp),
            modifier = Modifier.padding(top = 8.dp))
        v.exits.forEach { e ->
            Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text(keepNumbersWhole("${e.name} · ${e.rule}"), style = ink)
                Text(keepNumbersWhole(e.away), style = soft.copy(color = if (e.near) p.amber else p.inkSoft,
                    fontWeight = if (e.near) FontWeight.SemiBold else FontWeight.Normal))
            }
        }
        v.progress?.let { g ->
            val pct = String.format(Locale.ENGLISH, "%.0f%%", g.fraction * 100)
            Box(Modifier.fillMaxWidth().padding(top = 8.dp).height(8.dp).background(p.rule, RoundedCornerShape(50))
                .semantics { contentDescription = "Index between the index stop and the target: $pct of the way" }) {
                Box(Modifier.fillMaxWidth(g.fraction).fillMaxHeight().background(p.verdigris, RoundedCornerShape(50)))
            }
            Text(keepNumbersWhole(g.label), style = soft.copy(fontSize = 11.sp), modifier = Modifier.padding(top = 2.dp))
        }
        Text(keepNumbersWhole(v.range), style = soft, modifier = Modifier.padding(top = 6.dp))
        Text(keepNumbersWhole(v.also), style = soft.copy(fontSize = 11.sp), modifier = Modifier.padding(top = 4.dp))
        Text(keepNumbersWhole(v.asOf), style = soft.copy(color = p.inkFaint, fontSize = 11.sp))
    }
}
