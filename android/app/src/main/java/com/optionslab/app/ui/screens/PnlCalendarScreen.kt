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
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
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
    // Which strategy's trips (All = the whole account), and month or year view.
    var owner by remember { mutableStateOf("All") }
    var yearView by remember { mutableStateOf(false) }
    val owners by model.orderOwners.collectAsState()
    LaunchedEffect(Unit) { model.refreshStrategies() }
    val trips by produceState<List<com.optionslab.engine.RoundTrips.Trip>>(emptyList(), live, tick) {
        value = withContext(Dispatchers.IO) { runCatching { com.optionslab.app.data.TradeBook.trips(live) }.getOrDefault(emptyList()) }
    }
    val ownerNames = remember(trips, owners) { trips.map { com.optionslab.app.data.TradeBook.ownerOf(it, owners) }.distinct().sorted() }

    // Keep today's figure fresh while the calendar is open.
    LaunchedEffect(live) { if (live) { if (com.optionslab.app.data.Broker.loggedIn) model.loadAccount(quiet = true) } else model.loadPaper(quiet = true) }
    val accountDays by produceState<Map<LocalDate, DailyPnl.Day>>(emptyMap(), live, tick) {
        value = withContext(Dispatchers.IO) { runCatching { DailyPnl.all(live) }.getOrDefault(emptyMap()) }
    }
    // One strategy: its realised round trips by the day they closed.
    val all = if (owner == "All") accountDays else remember(trips, owners, owner) {
        trips.filter { com.optionslab.app.data.TradeBook.ownerOf(it, owners) == owner }.groupBy { it.day }
            .mapValues { (d, ts) -> DailyPnl.Day(d, Math.round(ts.sumOf { it.net } * 100) / 100.0, ts.size) }
    }
    val first = all.keys.minOrNull()?.let { YearMonth.from(it) }
    // Today is shown live from the account as it stands now, not from the last recorded reading.
    val today = Market.today()
    val paperNow by model.paper.collectAsState()
    val accountNow by model.account.collectAsState()
    val todayLive: DailyPnl.Day? = if (live) (accountNow as? com.optionslab.app.ui.Load.Done)?.value
        ?.takeIf { it.book.net.isNotEmpty() || it.trades.isNotEmpty() }?.let { DailyPnl.Day(today, it.book.m2m, it.trades.size) }
        else (paperNow as? com.optionslab.app.ui.Load.Done)?.value?.let { sn ->
            val pnl = sn.funds.todayRealizedPnl + sn.funds.m2mUnrealized
            if (sn.trades.isNotEmpty() || pnl != 0.0 || sn.positions.positions.any { it.quantity != 0 }) DailyPnl.Day(today, pnl, sn.trades.size) else null
        }
    val days = all.filterKeys { YearMonth.from(it) == month }.let { m ->
        if (owner == "All" && todayLive != null && YearMonth.from(today) == month) m + (today to todayLive) else m
    }
    // While today's figure is open, keep it live.
    LaunchedEffect(picked, live) {
        while (picked == today) {
            if (live) { if (com.optionslab.app.data.Broker.loggedIn) model.loadAccount(quiet = true) } else model.loadPaper(quiet = true)
            delay(10_000)
        }
    }
    val prev = all.filterKeys { YearMonth.from(it) == month.minusMonths(1) }
    // CSV export of every day shown (this account, this filter), to a file the owner picks.
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val csv = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            val rows = all.values.sortedBy { it.date }
            val text = buildString {
                append("date,account,strategy,pnl,trades\n")
                rows.forEach { d -> append("${d.date},${if (live) "Zerodha" else "Paper"},${owner.replace(',', ' ')},${"%.2f".format(Locale.ENGLISH, d.pnl)},${d.trades}\n") }
            }
            val ok = runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) } }.isSuccess
            if (ok) com.optionslab.app.work.Alerts.success("Exported ${rows.size} days.") else com.optionslab.app.work.Alerts.error("Could not write the file.")
        }
    }

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
                FilterRow(ownerNames, owner, yearView, onOwner = { owner = it; picked = null }, onYear = { yearView = it; picked = null },
                    onExport = { csv.launch("iraalgo-pnl-${if (live) "zerodha" else "paper"}-${month.year}.csv") })
                if (yearView) {
                    YearGrid(month.year, all, thisMonth) { m -> month = m; yearView = false }
                } else {
                    Headline(month, days, prev)
                    Spacer(Modifier.height(12.dp))
                    MonthGrid(month, days, picked, animKey = "$live|$month|$owner") { d -> picked = if (picked == d) null else d }
                    Legend()
                }
                if (owner != "All") Note("$owner: realised round trips by the day they closed, after charges.", Modifier.padding(top = 6.dp))
            }
        }
        item { Summary(month, days) }
        item { YearStrip(month.year, all, live) }
        item { StrategyComparison(trips, owners) }
        item { ChargesCard(live, month, tick) }
        item { JournalCard(trips) }
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
    val tiles = remember { HashMap<LocalDate, androidx.compose.ui.geometry.Rect>() }
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(Modifier.fillMaxWidth().onGloballyPositioned { origin = it.positionInWindow() }) {
        Column(Modifier.fillMaxWidth()) { MonthRows(month, days, picked, animKey, tiles, onPick) }
        // Exactly one tooltip, over the picked tile: choosing another day moves it, never leaves the old one behind.
        if (picked != null) tiles[picked]?.let { r -> DayTip(picked, days[picked], r.translate(-origin.x, -origin.y), picked == Market.today()) { onPick(picked) } }
    }
}

@Composable
private fun MonthRows(month: YearMonth, days: Map<LocalDate, DailyPnl.Day>, picked: LocalDate?, animKey: String,
                      tiles: MutableMap<LocalDate, androidx.compose.ui.geometry.Rect>, onPick: (LocalDate) -> Unit) {
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
                if (d == null) Spacer(Modifier.size(TILE)) else Box(Modifier.onGloballyPositioned { tiles[d] = it.boundsInWindow() }) {
                    DayTile(d, days[d], biggest, d == today, d == picked, !Market.isWeekday(d) || Holidays.isHoliday(d), animKey, index++) { onPick(d) }
                }
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
    }
}

private fun lerp(a: Color, b: Color, t: Float) = Color(
    a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, a.alpha + (b.alpha - a.alpha) * t)

/**
 * The figure for the tapped day, floating just above its tile. Today's is live (the
 * account as it stands, refreshed every 10 s) and stays until tapped away; another
 * day's leaves after 3 s.
 */
@Composable
private fun DayTip(d: LocalDate, day: DailyPnl.Day?, tile: androidx.compose.ui.geometry.Rect, isToday: Boolean, onGone: () -> Unit) {
    val p = LocalPalette.current
    LaunchedEffect(d) { if (!isToday) { delay(3_000); onGone() } }
    val gap = with(androidx.compose.ui.platform.LocalDensity.current) { 6.dp.roundToPx() }
    val place = remember(tile) {
        object : androidx.compose.ui.window.PopupPositionProvider {
            override fun calculatePosition(anchorBounds: androidx.compose.ui.unit.IntRect, windowSize: androidx.compose.ui.unit.IntSize,
                                           layoutDirection: androidx.compose.ui.unit.LayoutDirection, popupContentSize: androidx.compose.ui.unit.IntSize): IntOffset {
                val x = anchorBounds.left + tile.center.x.toInt() - popupContentSize.width / 2
                val above = anchorBounds.top + tile.top.toInt() - popupContentSize.height - gap
                val y = if (above >= 0) above else anchorBounds.top + tile.bottom.toInt() + gap
                return IntOffset(x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)), y)
            }
        }
    }
    // Only its own timer or a tap on its day closes it, so tapping another day simply moves it there.
    Popup(popupPositionProvider = place, properties = androidx.compose.ui.window.PopupProperties(focusable = false, dismissOnClickOutside = false)) {
        Column(Modifier.background(p.ink, RoundedCornerShape(9.dp)).padding(horizontal = 10.dp, vertical = 7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)}, ${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)}",
                    style = Type.label.copy(color = p.paper.copy(alpha = 0.7f), fontSize = 9.5.sp))
                if (isToday) {
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.size(5.dp).background(Color(0xFF3DDC97), CircleShape))
                    Text(" LIVE", style = Type.label.copy(color = Color(0xFF3DDC97), fontSize = 8.5.sp, fontWeight = FontWeight.Bold))
                }
            }
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


/** Strategy filter, month/year switch and CSV export. */
@Composable
private fun FilterRow(owners: List<String>, owner: String, yearView: Boolean, onOwner: (String) -> Unit, onYear: (Boolean) -> Unit, onExport: () -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(top = 10.dp).horizontalScroll(androidx.compose.foundation.rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        (listOf("All") + owners).forEach { o ->
            val on = o == owner
            Text(o, style = Type.label.copy(color = if (on) p.paper else p.ink, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) p.ink else p.chip).clickable { onOwner(o) }.padding(horizontal = 10.dp, vertical = 5.dp))
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Segmented(listOf("Month", "Year"), if (yearView) 1 else 0) { onYear(it == 1) }
        Spacer(Modifier.weight(1f))
        Text("Export CSV", style = Type.label.copy(color = p.ink, fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
            modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onExport).padding(horizontal = 8.dp, vertical = 5.dp))
    }
}

/** Twelve months as tiles, each shaded by its net; tap one to open it. */
@Composable
private fun YearGrid(year: Int, all: Map<LocalDate, DailyPnl.Day>, thisMonth: YearMonth, onPick: (YearMonth) -> Unit) {
    val p = LocalPalette.current
    val byMonth = (1..12).associateWith { m -> all.filterKeys { it.year == year && it.monthValue == m }.values }
    val biggest = byMonth.values.maxOfOrNull { v -> abs(v.sumOf { it.pnl }) }?.takeIf { it > 0 } ?: 1.0
    val total = byMonth.values.sumOf { v -> v.sumOf { it.pnl } }
    Column(Modifier.padding(top = 12.dp)) {
        Eyebrow("Net P&L · $year")
        Text(rupees(total), style = Type.figure.copy(color = if (total >= 0) p.verdigris else p.oxblood, fontSize = 22.sp, fontWeight = FontWeight.SemiBold))
        (1..12).chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { m ->
                    val ym = YearMonth.of(year, m)
                    val v = byMonth.getValue(m); val net = v.sumOf { it.pnl }
                    val depth = if (v.isEmpty()) 0f else (0.22f + 0.78f * sqrt(abs(net) / biggest).toFloat()).coerceIn(0.22f, 1f)
                    val bg = when { v.isEmpty() -> p.chip; net >= 0 -> lerp(p.chip, p.verdigris, depth); else -> lerp(p.chip, p.oxblood, depth) }
                    val fg = if (depth > 0.55f) Color.White else p.ink
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(bg).clickable(enabled = ym <= thisMonth) { onPick(ym) }.padding(8.dp)) {
                        Text(ym.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH), style = Type.label.copy(color = fg, fontSize = 10.sp, fontWeight = FontWeight.SemiBold))
                        Text(if (v.isEmpty()) "–" else short(net), style = Type.figure.copy(color = fg, fontSize = 11.sp))
                    }
                }
            }
        }
    }
}

/** Each strategy's round trips side by side (net of charges). */
@Composable
private fun StrategyComparison(trips: List<com.optionslab.engine.RoundTrips.Trip>, owners: Map<String, String>) {
    val p = LocalPalette.current
    val groups = trips.groupBy { com.optionslab.app.data.TradeBook.ownerOf(it, owners) }.mapValues { com.optionslab.engine.RoundTrips.stats(it.value) }
        .entries.sortedByDescending { it.value.net }
    LedgerCard {
        Eyebrow("Strategies compared · all time")
        if (groups.isEmpty()) { Note("No completed trades yet.", Modifier.padding(top = 6.dp)); return@LedgerCard }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            listOf("Strategy" to 1.6f, "Trips" to 0.8f, "Win" to 0.8f, "PF" to 0.8f, "Net" to 1.2f).forEach { (h, w) ->
                Text(h, style = Type.label.copy(color = p.inkSoft, fontSize = 9.5.sp), modifier = Modifier.weight(w), textAlign = if (h == "Strategy") TextAlign.Start else TextAlign.End)
            }
        }
        groups.forEach { (name, st) ->
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = Type.bodySmall.copy(color = p.ink, fontWeight = FontWeight.SemiBold, fontSize = 12.sp), modifier = Modifier.weight(1.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${st.trips}", style = Type.figure.copy(fontSize = 11.sp), modifier = Modifier.weight(0.8f), textAlign = TextAlign.End)
                Text("${Math.round(100 * st.winRate)}%", style = Type.figure.copy(fontSize = 11.sp), modifier = Modifier.weight(0.8f), textAlign = TextAlign.End)
                Text(st.profitFactor?.let { String.format(Locale.ENGLISH, "%.2f", it) } ?: "∞", style = Type.figure.copy(fontSize = 11.sp), modifier = Modifier.weight(0.8f), textAlign = TextAlign.End)
                Text(short(st.net), style = Type.figure.copy(fontSize = 11.sp, color = if (st.net >= 0) p.verdigris else p.oxblood, fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.weight(1.2f), textAlign = TextAlign.End)
            }
            Text("avg ${short(st.average)} · best ${short(st.best)} · worst ${short(st.worst)} · drawdown ${short(st.maxDrawdown)} · charges ${short(st.charges)}",
                style = Type.label.copy(color = p.inkSoft, fontSize = 9.5.sp))
        }
    }
}

/** What the month's trades cost, line by line. */
@Composable
private fun ChargesCard(live: Boolean, month: YearMonth, tick: Int) {
    val p = LocalPalette.current
    val lines by produceState<Map<String, Double>>(emptyMap(), live, month, tick) {
        value = withContext(Dispatchers.IO) { runCatching { com.optionslab.app.data.TradeBook.charges(live, month) }.getOrDefault(emptyMap()) }
    }
    LedgerCard {
        Eyebrow("Charges · ${month.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)}")
        if (lines.isEmpty()) { Note("No trades this month.", Modifier.padding(top = 6.dp)); return@LedgerCard }
        val total = lines.values.sum()
        Text(rupees(-total), style = Type.figure.copy(color = p.oxblood, fontSize = 20.sp, fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(top = 4.dp))
        lines.forEach { (k, v) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(k, style = Type.bodySmall.copy(color = p.inkSoft, fontSize = 12.sp), modifier = Modifier.weight(1f))
                Text(String.format(Locale.ENGLISH, "₹%,.2f", v), style = Type.figure.copy(fontSize = 12.sp))
            }
        }
        Note(if (live) "Zerodha: estimated with the F&O schedule from the trades IraAlgo recorded; your contract note is the final word."
            else "Paper: exactly what the paper account charged.", Modifier.padding(top = 6.dp))
    }
}

/** P&L by journal tag, and the latest notes. */
@Composable
private fun JournalCard(trips: List<com.optionslab.engine.RoundTrips.Trip>) {
    val p = LocalPalette.current
    val stats = remember(trips) { com.optionslab.app.data.Journal.byTag(trips) }
    val notes = remember(trips) { com.optionslab.app.data.Journal.all().values.filter { it.note.isNotBlank() }.sortedByDescending { it.at }.take(5) }
    LedgerCard {
        Eyebrow("Journal")
        if (stats.isEmpty() && notes.isEmpty()) {
            Note("Tap any trade (Trade tab or Home) and choose Journal to add a note and tags: setup, mistakes, mood. What each tag makes shows here.", Modifier.padding(top = 6.dp))
            return@LedgerCard
        }
        stats.forEach { t ->
            Row(Modifier.fillMaxWidth().padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(t.tag, style = Type.bodySmall.copy(color = p.ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
                Text("${t.trips} trips · ${Math.round(100.0 * t.wins / t.trips.coerceAtLeast(1))}% won", style = Type.label.copy(color = p.inkSoft, fontSize = 10.sp))
                Spacer(Modifier.width(10.dp))
                Text(short(t.net), style = Type.figure.copy(fontSize = 12.sp, color = if (t.net >= 0) p.verdigris else p.oxblood, fontWeight = FontWeight.SemiBold))
            }
        }
        if (notes.isNotEmpty()) Eyebrow("Latest notes", Modifier.padding(top = 10.dp))
        notes.forEach { e -> Text("• " + e.note + if (e.tags.isNotEmpty()) "  [${e.tags.joinToString()}]" else "", style = Type.bodySmall.copy(color = p.ink, fontSize = 12.sp), modifier = Modifier.padding(top = 3.dp)) }
    }
}
