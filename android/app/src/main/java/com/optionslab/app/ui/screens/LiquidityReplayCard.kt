package com.optionslab.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.optionslab.app.data.OrbArms
import com.optionslab.app.ui.components.AlertDialog
import com.optionslab.app.ui.components.TextButton
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import com.optionslab.engine.orb.LiquidityRules
import com.optionslab.ira.LiquidityReplay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * Where a closed Liquidity 15+5 paper trade's replay reads its candles: the index's 1-minute bars the arm itself read
 * ([OrbArms.liquidityMinutesKept], else the same read Jarvis's liquidity map makes, [OrbArms.liquidityMinutesFor]) and the
 * option's own 1-minute candles of the day; the research lesson from [com.optionslab.ira.BotTrades.lesson]. Today's trades
 * only (the feed's 1-minute candles are the day's). Reads only: nothing is kept, placed, closed or armed.
 */
internal object LiquidityReplayData {
    suspend fun load(p: OrbArms.Position): LiquidityReplay.Replay? {
        if (com.optionslab.app.BuildConfig.GOLD) return null
        if (p.open || p.live) return null
        val arm = LiquidityRules.BOOKS.firstOrNull { it.source == (LiquidityRules.RENAMED[p.arm] ?: p.arm) } ?: return null
        val und = LiquidityRules.underlyingOf(arm)
        val now = com.optionslab.app.data.Market.now().toLocalDateTime()
        val today = p.day == now.toLocalDate()
        val c = runCatching { com.optionslab.app.data.Paper.contractOf(p.symbol) }.getOrNull()
        val index = if (!today) emptyList()
            else OrbArms.liquidityMinutesKept(und, now) ?: runCatching { OrbArms.liquidityMinutesFor(und, now) }.getOrDefault(emptyList())
        val option = if (!today || c == null) emptyList()
            else runCatching { LiquidityCache.toBars(com.optionslab.app.data.Net.intraday(c.feedKey)) }.getOrDefault(emptyList())
        return LiquidityReplay.of(com.optionslab.app.ira.IraBots.tradeOf(p), c?.strike, index, option)
    }
}

/** A closed Liquidity trade's replay, read by [load] off the main thread (nothing in the GOLD build). */
@Composable
internal fun LiquidityReplayDialog(p: OrbArms.Position, load: suspend (OrbArms.Position) -> LiquidityReplay.Replay?, onClose: () -> Unit) {
    if (com.optionslab.app.BuildConfig.GOLD) return
    var replay by remember(p) { mutableStateOf<LiquidityReplay.Replay?>(null) }
    var failed by remember(p) { mutableStateOf(false) }
    LaunchedEffect(p) {
        val r = withContext(Dispatchers.IO) { runCatching { load(p) }.getOrNull() }
        if (r == null) failed = true else replay = r
    }
    LiquidityReplayCard(replay, if (failed) "No replay for this trade: its candles could not be read." else null, onClose)
}

/** The replay card: the chart, the trade in words (figures kept whole) and its research lesson. Shows only; changes nothing. */
@Composable
internal fun LiquidityReplayCard(replay: LiquidityReplay.Replay?, error: String?, onClose: () -> Unit) {
    val p = LocalPalette.current
    AlertDialog(
        onDismissRequest = onClose,
        properties = DialogProperties(securePolicy = com.optionslab.app.security.Capture.policy),
        title = { Text(keepNumbersWhole(replay?.title ?: "Replay"), style = Type.title) },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()).testTag("liquidity-replay")) {
                val small = Type.bodySmall.copy(color = p.ink, fontSize = 12.sp)
                val soft = Type.bodySmall.copy(color = p.inkSoft, fontSize = 12.sp)
                if (replay == null) {
                    Text(error ?: "Reading the trade's candles…", style = soft)
                } else {
                    ReplayChart(replay, Modifier.fillMaxWidth().height(if (replay.premium.isEmpty() && replay.best == null && replay.worst == null) 140.dp else 210.dp))
                    Text(keepNumbersWhole(legend(replay)), style = soft.copy(fontSize = 11.sp), modifier = Modifier.padding(top = 4.dp, bottom = 6.dp))
                    replay.lines.forEach { Text(keepNumbersWhole(it), style = small, modifier = Modifier.padding(vertical = 2.dp)) }
                    Text(keepNumbersWhole("Against the research: " + (replay.lesson ?: "no lesson for this trade.")),
                        style = small.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(top = 8.dp))
                    Text("A replay of a closed paper trade: it reads the candles and changes nothing.", style = soft,
                        modifier = Modifier.padding(top = 6.dp))
                }
            }
        },
        confirmButton = { TextButton(onClose) { Text("Close") } },
    )
}

/** What the chart's marks are, in a line (only those drawn). */
internal fun legend(r: LiquidityReplay.Replay): String = listOfNotNull(
    "shaded: held",
    r.level?.let { "gold line: level" },
    r.indexStop?.let { "red dashes: index stop" },
    r.target?.let { "dots: target" },
    "● entry ■ exit",
    if (r.premium.isNotEmpty() || r.best != null || r.worst != null) "below: premium" else null,
    r.best?.let { "▲ best" },
    r.worst?.let { "▼ worst" },
).joinToString(" · ")

/** The index candles over the window with the levels, the held span, the entry and exit; the premium under them. */
@Composable
private fun ReplayChart(r: LiquidityReplay.Replay, modifier: Modifier) {
    val p = LocalPalette.current
    Canvas(modifier.background(p.paperDeep, RoundedCornerShape(8.dp))
        .semantics { contentDescription = "Trade replay chart: the index, the broken level, the index stop, the entry and the exit" }) {
        val l = LiquidityReplay.layout(r, size.width.toInt(), size.height.toInt(), 6.dp.toPx().toInt()) ?: return@Canvas
        drawRect(p.gold.copy(alpha = 0.12f), Offset(l.holdFrom, 0f), Size(max(1f, l.holdTo - l.holdFrom), size.height))
        for (k in l.candles) {
            val c = (if (k.up) p.verdigris else p.oxblood).copy(alpha = 0.8f)
            drawLine(c, Offset(k.x, k.high), Offset(k.x, k.low), 1f)
            drawRect(c, Offset(k.x - l.candleWidth / 2, k.top), Size(l.candleWidth, max(1f, k.bottom - k.top)))
        }
        for (ln in l.lines) {
            val color = when (ln.kind) {
                LiquidityReplay.LineKind.LEVEL -> p.gold
                LiquidityReplay.LineKind.INDEX_STOP -> p.oxblood
                LiquidityReplay.LineKind.TARGET -> p.brass
            }
            val effect: PathEffect? = when (ln.kind) {
                LiquidityReplay.LineKind.LEVEL -> null
                LiquidityReplay.LineKind.INDEX_STOP -> PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
                LiquidityReplay.LineKind.TARGET -> PathEffect.dashPathEffect(floatArrayOf(2f, 5f))
            }
            drawLine(color, Offset(0f, ln.y), Offset(size.width, ln.y), if (effect == null) 2f else 1.5f, pathEffect = effect)
        }
        val rad = 4.dp.toPx()
        l.entry?.let { drawCircle(p.ink, rad, Offset(it.x, it.y)) }
        l.exit?.let { drawRect(p.ink, Offset(it.x - rad, it.y - rad), Size(rad * 2, rad * 2)) }
        l.premiumTop?.let { top ->
            drawLine(p.inkFaint, Offset(0f, top - 3f), Offset(size.width, top - 3f), 1f)
            if (l.premium.size > 1) {
                val path = Path().apply {
                    moveTo(l.premium.first().x, l.premium.first().y)
                    for (i in 1 until l.premium.size) lineTo(l.premium[i].x, l.premium[i].y)
                }
                drawPath(path, p.ink, style = Stroke(1.5f))
            }
            l.premiumEntry?.let { drawCircle(p.ink, rad * 0.8f, Offset(it.x, it.y)) }
            l.premiumExit?.let { drawRect(p.ink, Offset(it.x - rad * 0.8f, it.y - rad * 0.8f), Size(rad * 1.6f, rad * 1.6f)) }
            l.best?.let { d ->
                drawPath(Path().apply { moveTo(d.x, d.y - rad); lineTo(d.x + rad, d.y + rad); lineTo(d.x - rad, d.y + rad); close() }, p.verdigris)
            }
            l.worst?.let { d ->
                drawPath(Path().apply { moveTo(d.x, d.y + rad); lineTo(d.x + rad, d.y - rad); lineTo(d.x - rad, d.y - rad); close() }, p.oxblood)
            }
        }
    }
}
