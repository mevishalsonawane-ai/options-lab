package com.optionslab.app.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import com.optionslab.app.data.DailyPnl
import com.optionslab.app.data.Holidays
import com.optionslab.app.data.Market
import com.optionslab.app.ui.AppModel
import com.optionslab.app.ui.Page
import com.optionslab.app.ui.components.LedgerCard
import com.optionslab.app.ui.components.Note
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.sqrt

private fun rupees(x: Double) = (if (x < 0) "−₹" else if (x > 0) "+₹" else "₹") + String.format(Locale.ENGLISH, "%,.0f", abs(x))

/** +6.4k / −18.4k / +1.2L: compact figures for the tiles and the week column. */
private fun short(x: Double): String {
    val a = abs(x); val s = if (x < 0) "−" else if (x > 0) "+" else ""
    return s + when {
        a >= 1e5 -> String.format(Locale.ENGLISH, "%.1fL", a / 1e5)
        a >= 1e3 -> String.format(Locale.ENGLISH, "%.1fk", a / 1e3)
        else -> String.format(Locale.ENGLISH, "%.0f", a)
    }
}

private fun mon(m: YearMonth) = m.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)

private val TILE = 26.dp

/**
 * The P&L calendar, drawn like Zerodha Console's and GitHub's grids: a small tile
 * per day, green for a profit and red for a loss, deeper the bigger the day, with
 * each week's total at the end of its row. Above it: the month's net and its
 * cumulative line; below it: the month's key figures and the year at a glance.
 * Paper and Zerodha each have their own calendar.
 */
@Composable
fun PnlCalendarScreen(model: AppModel) {
    val s by model.settings.collectAsState()
    val tick by model.pnlDays.collectAsState()
    var live by remember { mutableStateOf(s.live) }
    val thisMonth = YearMonth.from(Market.today())
    var month by remember { mutableStateOf(thisMonth) }
    var picked by remember { mutableStateOf<LocalDate?>(null) }

    // Keep today's figure fresh while the calendar is open.
    LaunchedEffect(live) { if (live) { if (com.optionslab.app.data.Broker.loggedIn) model.loadAccount(quiet = true) } else model.loadPaper(quiet = true) }
    val all by produceState<Map<LocalDate, DailyPnl.Day>>(emptyMap(), live, tick) {
        value = withContext(Dispatchers.IO) { runCatching { DailyPnl.all(live) }.getOrDefault(emptyMap()) }
    }
    val first = all.keys.minOrNull()?.let { YearMonth.from(it) }
    val days = all.filterKeys { YearMonth.from(it) == month }
    val prev = all.filterKeys { YearMonth.from(it) == month.minusMonths(1) }

    Page {
        item {
            LedgerCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Segmented(listOf("Paper", "Zerodha"), if (live) 1 else 0) { i -> live = i == 1; picked = null }
                    Spacer(Modifier.weight(1f))
                    NavArrow("‹", first == null || month > first) { month = month.minusMonths(1); picked = null }
                    Text("${mon(month)} ${month.year}", style = Type.body.copy(color = LocalPalette.current.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                        textAlign = TextAlign.Center, modifier = Modifier.width(78.dp))
                    NavArrow("›", month < thisMonth) { month = month.plusMonths(1); picked = null }
                }
                Headline(month, days, prev)
                Spacer(Modifier.height(12.dp))
                MonthGrid(month, days, picked, animKey = "$live|$month") { d -> picked = if (picked == d) null else d }
                Legend()
            }
        }
        item { Summary(month, days) }
        item { YearStrip(month.year, all, live) }
    }
}

@Composable
private fun Segmented(options: List<String>, selected: Int, onPick: (Int) -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.background(p.chip, RoundedCornerShape(50)).padding(2.dp)) {
        options.forEachIndexed { i, o ->
            val on = i == selected
            Text(o, style = Type.label.copy(color = if (on) p.ink else p.inkSoft, fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) p.card else Color.Transparent)
                    .clickable { onPick(i) }.padding(horizontal = 11.dp, vertical = 5.dp))
        }
    }
}

@Composable
private fun NavArrow(label: String, enabled: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(Modifier.size(28.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, style = Type.title.copy(color = if (enabled) p.ink else p.inkFaint, fontSize = 17.sp))
    }
}

@Composable
private fun Eyebrow(text: String, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Text(text.uppercase(Locale.ENGLISH), style = Type.label.copy(color = p.inkSoft, fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.9.sp),
        modifier = modifier, maxLines = 1)
}

/** The month's net, its change on the month before, and the cumulative P&L line. */
@Composable
private fun Headline(month: YearMonth, days: Map<LocalDate, DailyPnl.Day>, prev: Map<LocalDate, DailyPnl.Day>) {
    val p = LocalPalette.current
    val net = days.values.sumOf { it.pnl }
    val tone = if (net >= 0) p.verdigris else p.oxblood
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Eyebrow("Net P&L · month")
            Text(rupees(net), style = Type.figure.copy(color = if (days.isEmpty()) p.inkFaint else tone, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.6).sp))
            Text(if (prev.isEmpty()) "No figures for ${mon(month.minusMonths(1))}" else "${rupees(net - prev.values.sumOf { it.pnl })} vs ${mon(month.minusMonths(1))}",
                style = Type.bodySmall.copy(color = p.inkSoft, fontSize = 10.5.sp))
        }
        // Cumulative P&L through the month, with its zero line.
        val pts = days.values.sortedBy { it.date }.runningFold(0 to 0.0) { acc, d -> d.date.dayOfMonth to acc.second + d.pnl }
        if (pts.size >= 2) {
            val zero = p.rule
            Canvas(Modifier.width(120.dp).height(34.dp)) {
                val last = month.lengthOfMonth().toFloat()
                val ys = pts.map { it.second }; val lo = minOf(0.0, ys.min()); val hi = maxOf(0.0, ys.max()); val span = (hi - lo).takeIf { it > 0 } ?: 1.0
                fun x(d: Int) = 2f + d / last * (size.width - 4f)
                fun y(v: Double) = (size.height - 3f) - ((v - lo) / span).toFloat() * (size.height - 6f)
                val line = Path().apply { pts.forEachIndexed { i, (d, v) -> if (i == 0) moveTo(x(d), y(v)) else lineTo(x(d), y(v)) } }
                val area = Path().apply { addPath(line); lineTo(x(pts.last().first), size.height); lineTo(x(0), size.height); close() }
                drawLine(zero, Offset(0f, y(0.0)), Offset(size.width, y(0.0)), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)))
                drawPath(area, Brush.verticalGradient(listOf(tone.copy(alpha = 0.28f), tone.copy(alpha = 0f))))
                drawPath(line, tone, style = Stroke(width = 3.2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawCircle(tone, 4.8f, Offset(x(pts.last().first), y(pts.last().second)))
            }
        }
    }
}

/** Monday-first weeks of 26 dp tiles, each week's net at the end of its row. */
@Composable
private fun MonthGrid(month: YearMonth, days: Map<LocalDate, DailyPnl.Day>, picked: LocalDate?, animKey: String, onPick: (LocalDate) -> Unit) {
    val p = LocalPalette.current
    val today = Market.today()
    val biggest = days.values.maxOfOrNull { abs(it.pnl) }?.takeIf { it > 0 } ?: 1.0
    val gap = 5.dp
    Row(verticalAlignment = Alignment.CenterVertically) {
        DayOfWeek.entries.forEach { d ->
            Text(d.getDisplayName(TextStyle.NARROW, Locale.ENGLISH), style = Type.label.copy(color = p.inkFaint, fontSize = 9.sp, fontWeight = FontWeight.SemiBold),
                textAlign = TextAlign.Center, modifier = Modifier.width(TILE))
            Spacer(Modifier.width(gap))
        }
        Spacer(Modifier.weight(1f))
        Eyebrow("Week")
    }
    val lead = month.atDay(1).dayOfWeek.value - 1
    val cells: List<LocalDate?> = List(lead) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
    var index = 0
    cells.chunked(7).forEach { week ->
        Row(Modifier.padding(top = gap), verticalAlignment = Alignment.CenterVertically) {
            (week + List(7 - week.size) { null }).forEach { d ->
                if (d == null) Spacer(Modifier.size(TILE)) else DayTile(d, days[d], biggest, d == today, d == picked,
                    !Market.isWeekday(d) || Holidays.isHoliday(d), animKey, index++) { onPick(d) }
                Spacer(Modifier.width(gap))
            }
            Spacer(Modifier.weight(1f))
            val w = week.filterNotNull().mapNotNull { days[it] }
            val sum = w.sumOf { it.pnl }
            Text(if (w.isEmpty()) "–" else short(sum), style = Type.figure.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                color = when { w.isEmpty() -> p.inkFaint; sum >= 0 -> p.verdigris; else -> p.oxblood }), maxLines = 1)
        }
    }
}

@Composable
private fun DayTile(d: LocalDate, day: DailyPnl.Day?, biggest: Double, today: Boolean, picked: Boolean, closed: Boolean,
                    animKey: String, index: Int, onTap: () -> Unit) {
    val p = LocalPalette.current
    // Tiles pop in one after another when the month or account changes.
    val appear = remember(animKey) { Animatable(0f) }
    LaunchedEffect(animKey) { delay(index * 9L); appear.animateTo(1f, tween(260)) }
    // Deeper for a bigger day; the square root keeps small days visible beside one big one.
    val depth = day?.let { if (it.pnl == 0.0) 0f else (0.22f + 0.78f * sqrt(abs(it.pnl) / biggest).toFloat()).coerceIn(0.22f, 1f) } ?: 0f
    val base = p.chip
    val bg = when {
        closed -> Color.Transparent
        day == null -> base
        day.pnl > 0 -> lerp(base, p.verdigris, depth)
        day.pnl < 0 -> lerp(base, p.oxblood, depth)
        else -> lerp(base, p.inkFaint, 0.35f)
    }
    val fg = if (day != null && depth > 0.55f) Color.White.copy(alpha = 0.92f) else p.inkFaint
    Box(Modifier.size(TILE)) {
        Box(
            Modifier.size(TILE)
                .graphicsLayer { alpha = appear.value; val s = 0.6f + 0.4f * appear.value; scaleX = s; scaleY = s }
                .then(if (picked) Modifier.border(2.dp, p.ink, RoundedCornerShape(6.dp)) else Modifier)
                .padding(if (picked) 2.dp else 0.dp)
                .background(bg, RoundedCornerShape(5.dp))
                .then(if (closed) Modifier.border(1.dp, p.rule, RoundedCornerShape(5.dp)) else Modifier)
                .clickable(enabled = !closed, onClick = onTap),
            contentAlignment = Alignment.Center,
        ) { Text("${d.dayOfMonth}", style = Type.label.copy(color = fg, fontSize = 8.5.sp, fontWeight = FontWeight.SemiBold)) }
        if (today) Box(Modifier.align(Alignment.TopEnd).offset(2.dp, (-2).dp).size(6.dp).background(p.ink, CircleShape).border(1.5.dp, p.card, CircleShape))
        if (picked) DayTip(d, day)
    }
}

private fun lerp(a: Color, b: Color, t: Float) = Color(
    a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, a.alpha + (b.alpha - a.alpha) * t)

/** The figure for a tapped day, floating above its tile; it leaves on its own after 3 s. */
@Composable
private fun DayTip(d: LocalDate, day: DailyPnl.Day?) {
    val p = LocalPalette.current
    var show by remember(d) { mutableStateOf(true) }
    LaunchedEffect(d) { delay(3_000); show = false }
    if (!show) return
    Popup(alignment = Alignment.TopCenter, offset = IntOffset(0, -150)) {
        Column(Modifier.background(p.ink, RoundedCornerShape(9.dp)).padding(horizontal = 10.dp, vertical = 7.dp)) {
            Text("${d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)}, ${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)}",
                style = Type.label.copy(color = p.paper.copy(alpha = 0.7f), fontSize = 9.5.sp))
            Text(day?.let { rupees(it.pnl) } ?: "No trades", style = Type.figure.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                color = when { day == null -> p.paper; day.pnl > 0 -> Color(0xFF3DDC97); day.pnl < 0 -> Color(0xFFFF7A73); else -> p.paper }))
            day?.takeIf { it.trades > 0 }?.let { Text("${it.trades} trade${if (it.trades == 1) "" else "s"}", style = Type.label.copy(color = p.paper.copy(alpha = 0.7f), fontSize = 9.5.sp)) }
        }
    }
}

@Composable
private fun Legend() {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Less", style = Type.label.copy(color = p.inkFaint, fontSize = 9.5.sp))
        Spacer(Modifier.weight(1f))
        listOf(1f, 0.62f, 0.36f).forEach { Swatch(lerp(p.chip, p.oxblood, it)) }
        Swatch(p.chip)
        listOf(0.36f, 0.62f, 1f).forEach { Swatch(lerp(p.chip, p.verdigris, it)) }
        Spacer(Modifier.weight(1f))
        Text("More", style = Type.label.copy(color = p.inkFaint, fontSize = 9.5.sp))
    }
}

@Composable
private fun Swatch(c: Color) = Box(Modifier.padding(horizontal = 1.dp).size(9.dp).background(c, RoundedCornerShape(2.dp)))

/** Nine key figures for the month, and the profit / loss split. */
@Composable
private fun Summary(month: YearMonth, days: Map<LocalDate, DailyPnl.Day>) {
    val p = LocalPalette.current
    val all = days.values.sortedBy { it.date }
    LedgerCard {
        Eyebrow("${month.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} summary")
        if (all.isEmpty()) { Note("No trading days recorded this month.", Modifier.padding(top = 6.dp)); return@LedgerCard }
        val net = all.sumOf { it.pnl }
        val wins = all.filter { it.pnl > 0 }; val losses = all.filter { it.pnl < 0 }
        val gp = wins.sumOf { it.pnl }; val gl = -losses.sumOf { it.pnl }
        var peak = 0.0; var cum = 0.0; var dd = 0.0
        all.forEach { cum += it.pnl; peak = maxOf(peak, cum); dd = minOf(dd, cum - peak) }
        var streak = 0; val sType = all.lastOrNull()?.pnl?.sign ?: 0.0
        for (d in all.asReversed()) { if (d.pnl.sign != sType || sType == 0.0) break; streak++ }
        val best = all.maxByOrNull { it.pnl }; val worst = all.minByOrNull { it.pnl }
        val trades = all.sumOf { it.trades }
        val tiles = listOf(
            Tile("Win rate", "${Math.round(100.0 * wins.size / all.size)}%", null, "${wins.size}W · ${losses.size}L"),
            Tile("P. factor", if (gl > 0) String.format(Locale.ENGLISH, "%.2f", gp / gl) else "∞", if (gp >= gl) p.verdigris else p.oxblood, "profit ÷ loss"),
            Tile("Avg / day", short(net / all.size), if (net >= 0) p.verdigris else p.oxblood, "${all.size} days"),
            Tile("Best day", best?.takeIf { it.pnl > 0 }?.let { short(it.pnl) } ?: "–", p.verdigris, best?.takeIf { it.pnl > 0 }?.let { "${it.date.dayOfMonth} ${mon(month)}" } ?: ""),
            Tile("Worst day", worst?.takeIf { it.pnl < 0 }?.let { short(it.pnl) } ?: "–", p.oxblood, worst?.takeIf { it.pnl < 0 }?.let { "${it.date.dayOfMonth} ${mon(month)}" } ?: ""),
            Tile("Drawdown", if (dd < 0) short(dd) else "₹0", if (dd < 0) p.oxblood else null, "from peak"),
            Tile("Streak", if (streak > 0) "$streak ${if (sType > 0) "W" else "L"}" else "–", if (sType > 0) p.verdigris else p.oxblood, "current"),
            Tile("Trades", "$trades", null, String.format(Locale.ENGLISH, "%.1f / day", trades.toDouble() / all.size)),
            Tile("Gross", short(gp), p.verdigris, "${short(-gl)} loss"),
        )
        tiles.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { t -> KpiTile(t, Modifier.weight(1f)) }
            }
        }
        // How the month's gross profit and loss split.
        val share = if (gp + gl > 0) (gp / (gp + gl)).toFloat() else 0.5f
        Row(Modifier.fillMaxWidth().padding(top = 10.dp).height(5.dp).clip(RoundedCornerShape(50))) {
            if (share > 0f) Box(Modifier.weight(share.coerceAtLeast(0.001f)).height(5.dp).background(p.verdigris))
            if (share < 1f) Box(Modifier.weight((1f - share).coerceAtLeast(0.001f)).height(5.dp).background(p.oxblood))
        }
        Note("Each day is realised plus open P&L after charges, as it stood at the day's last reading.", Modifier.padding(top = 8.dp))
    }
}

private data class Tile(val label: String, val value: String, val tone: Color?, val sub: String)

@Composable
private fun KpiTile(t: Tile, modifier: Modifier) {
    val p = LocalPalette.current
    Column(modifier.background(p.chip, RoundedCornerShape(10.dp)).padding(horizontal = 9.dp, vertical = 8.dp)) {
        Eyebrow(t.label)
        Text(t.value, style = Type.figure.copy(color = t.tone ?: p.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold), maxLines = 1,
            modifier = Modifier.padding(top = 3.dp))
        if (t.sub.isNotEmpty()) Text(t.sub, style = Type.label.copy(color = p.inkSoft, fontSize = 9.5.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Every weekday of the year as a small square, five rows a week, like a contribution graph. */
@Composable
private fun YearStrip(year: Int, all: Map<LocalDate, DailyPnl.Day>, live: Boolean) {
    val p = LocalPalette.current
    val inYear = all.filterKeys { it.year == year }
    val biggest = inYear.values.maxOfOrNull { abs(it.pnl) }?.takeIf { it > 0 } ?: 1.0
    LedgerCard {
        Eyebrow("$year at a glance")
        val empty = p.chip; val g = p.verdigris; val r = p.oxblood
        Canvas(Modifier.fillMaxWidth().height(46.dp).padding(top = 8.dp)) {
            var start = LocalDate.of(year, 1, 1)
            while (start.dayOfWeek != DayOfWeek.MONDAY) start = start.minusDays(1)
            val weeks = ((LocalDate.of(year, 12, 31).toEpochDay() - start.toEpochDay()) / 7 + 1).toInt()
            val gapPx = 1.5.dp.toPx()
            val cell = minOf((size.width - gapPx * (weeks - 1)) / weeks, (size.height - gapPx * 4) / 5)
            for (w in 0 until weeks) for (dow in 0 until 5) {
                val d = start.plusDays((w * 7 + dow).toLong())
                if (d.year != year) continue
                val v = inYear[d]
                val depth = v?.let { (0.22f + 0.78f * sqrt(abs(it.pnl) / biggest).toFloat()).coerceIn(0.22f, 1f) } ?: 0f
                val c = when { v == null || v.pnl == 0.0 -> empty; v.pnl > 0 -> lerp(empty, g, depth); else -> lerp(empty, r, depth) }
                drawRoundRect(c, Offset(w * (cell + gapPx), dow * (cell + gapPx)), Size(cell, cell), CornerRadius(cell * 0.2f))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("Jan", "Mar", "May", "Jul", "Sep", "Nov").forEach { Text(it, style = Type.label.copy(color = p.inkFaint, fontSize = 8.5.sp)) }
        }
        if (live) Note("Zerodha does not share past days' P&L with apps; this fills from the days IraAlgo recorded it.", Modifier.padding(top = 8.dp))
    }
}
