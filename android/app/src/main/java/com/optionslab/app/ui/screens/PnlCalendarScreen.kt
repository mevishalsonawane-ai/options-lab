package com.optionslab.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import kotlin.math.sqrt

private fun rupees(x: Double) = (if (x < 0) "−₹" else if (x > 0) "+₹" else "₹") + String.format(Locale.ENGLISH, "%,.0f", abs(x))

/**
 * The P&L calendar, as Zerodha Console's and GitHub's contribution grids draw
 * it: one small box per day, green for a profit and red for a loss, deeper the
 * bigger the day. Tap a day for its figure; the month's summary sits below.
 * Paper and Zerodha each have their own calendar.
 */
@Composable
fun PnlCalendarScreen(model: AppModel) {
    val p = LocalPalette.current
    val s by model.settings.collectAsState()
    val tick by model.pnlDays.collectAsState()
    var live by remember { mutableStateOf(s.live) }
    val thisMonth = YearMonth.from(Market.today())
    var month by remember { mutableStateOf(thisMonth) }
    var picked by remember { mutableStateOf<LocalDate?>(null) }

    // Keep today's figure fresh while the calendar is open.
    LaunchedEffect(live) { if (live) { if (com.optionslab.app.data.Broker.loggedIn) model.loadAccount(quiet = true) } else model.loadPaper(quiet = true) }
    val days by produceState<Map<LocalDate, DailyPnl.Day>>(emptyMap(), live, month, tick) {
        value = withContext(Dispatchers.IO) { runCatching { DailyPnl.month(live, month) }.getOrDefault(emptyMap()) }
    }
    val first by produceState<YearMonth?>(null, live, tick) { value = withContext(Dispatchers.IO) { runCatching { DailyPnl.firstMonth(live) }.getOrNull() } }

    Page {
        item {
            LedgerCard(title = "P&L calendar") {
                ParamTokens("Account", listOf("Paper" to !live, "Zerodha" to live)) { i -> live = i == 1; picked = null }
                // Month navigation.
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    val canBack = first == null || month > (first ?: month)
                    NavButton("‹", canBack) { month = month.minusMonths(1); picked = null }
                    Text(month.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + month.year,
                        style = Type.title.copy(color = p.ink, fontSize = 17.sp), textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                    NavButton("›", month < thisMonth) { month = month.plusMonths(1); picked = null }
                }
                Spacer(Modifier.padding(top = 10.dp))
                MonthGrid(month, days, picked) { d -> picked = if (picked == d) null else d }
                Legend()
                if (live) Note("Zerodha does not share past days' P&L with apps, so this calendar fills from the days IraAlgo recorded it (while the app or the market watch ran).",
                    Modifier.padding(top = 8.dp))
            }
        }
        item { MonthSummary(month, days, live) }
    }
}

@Composable
private fun NavButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        Modifier.size(40.dp).background(if (enabled) p.chip else Color.Transparent, RoundedCornerShape(20.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = Type.title.copy(color = if (enabled) p.ink else p.inkFaint, fontSize = 22.sp)) }
}

/** Monday-first weeks; each day a square, shaded by how big its result is against the month's biggest day. */
@Composable
private fun MonthGrid(month: YearMonth, days: Map<LocalDate, DailyPnl.Day>, picked: LocalDate?, onPick: (LocalDate) -> Unit) {
    val p = LocalPalette.current
    val today = Market.today()
    val biggest = days.values.maxOfOrNull { abs(it.pnl) }?.takeIf { it > 0 } ?: 1.0
    Row(Modifier.fillMaxWidth()) {
        DayOfWeek.entries.forEach { d ->
            Text(d.getDisplayName(TextStyle.NARROW, Locale.ENGLISH), style = Type.label.copy(color = p.inkFaint, fontSize = 11.sp),
                textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
        }
    }
    val lead = month.atDay(1).dayOfWeek.value - 1
    val cells = List(lead) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
    cells.chunked(7).forEach { week ->
        Row(Modifier.fillMaxWidth().padding(top = 5.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            (week + List(7 - week.size) { null }).forEach { d ->
                Box(Modifier.weight(1f).aspectRatio(1f)) {
                    if (d != null) DayBox(d, days[d], biggest, d == today, d == picked, !Market.isWeekday(d) || Holidays.isHoliday(d)) { onPick(d) }
                }
            }
        }
    }
}

@Composable
private fun DayBox(d: LocalDate, day: DailyPnl.Day?, biggest: Double, today: Boolean, picked: Boolean, closed: Boolean, onTap: () -> Unit) {
    val p = LocalPalette.current
    // Four depths, as a contribution grid: the square root keeps small days visible next to one big day.
    val depth = day?.let { if (it.pnl == 0.0) 0f else (0.28f + 0.72f * sqrt(abs(it.pnl) / biggest).toFloat()).coerceIn(0.28f, 1f) } ?: 0f
    val bg = when {
        day == null -> if (closed) p.chip.copy(alpha = 0.4f) else p.chip
        day.pnl > 0 -> p.verdigris.copy(alpha = depth)
        day.pnl < 0 -> p.oxblood.copy(alpha = depth)
        else -> p.inkFaint.copy(alpha = 0.35f)
    }
    val fg = if (day != null && depth > 0.55f) Color.White else if (closed) p.inkFaint else p.inkSoft
    Box(
        Modifier.fillMaxWidth().aspectRatio(1f).background(bg, RoundedCornerShape(6.dp))
            .then(if (today || picked) Modifier.border(BorderStroke(if (picked) 2.dp else 1.dp, p.ink), RoundedCornerShape(6.dp)) else Modifier)
            .clickable(onClick = onTap),
        contentAlignment = Alignment.TopStart,
    ) {
        Text("${d.dayOfMonth}", style = Type.label.copy(color = fg, fontSize = 10.sp), modifier = Modifier.padding(start = 4.dp, top = 2.dp))
        if (picked) DayTip(d, day) {}
    }
}

/** The figure for a tapped day, floating above its box; it fades on its own. */
@Composable
private fun DayTip(d: LocalDate, day: DailyPnl.Day?, onGone: () -> Unit) {
    val p = LocalPalette.current
    var show by remember(d) { mutableStateOf(true) }
    LaunchedEffect(d) { delay(3_000); show = false; onGone() }
    if (!show) return
    Popup(alignment = Alignment.BottomCenter, offset = IntOffset(0, -160)) {
        Column(
            Modifier.background(p.ink, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("${d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)} ${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)}",
                style = Type.label.copy(color = p.paper, fontSize = 11.sp))
            Text(day?.let { rupees(it.pnl) } ?: "No trades", style = Type.figure.copy(
                color = when { day == null -> p.paper; day.pnl > 0 -> Color(0xFF3DDC97); day.pnl < 0 -> Color(0xFFFF7A73); else -> p.paper },
                fontSize = 16.sp, fontWeight = FontWeight.Bold))
            day?.takeIf { it.trades > 0 }?.let { Text("${it.trades} trade${if (it.trades == 1) "" else "s"}", style = Type.label.copy(color = p.paper, fontSize = 11.sp)) }
        }
    }
}

@Composable
private fun Legend() {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Text("Loss", style = Type.label.copy(color = p.inkFaint, fontSize = 11.sp))
        Spacer(Modifier.width(6.dp))
        listOf(1f, 0.7f, 0.45f, 0.28f).forEach { a -> Swatch(p.oxblood.copy(alpha = a)) }
        Swatch(p.chip)
        listOf(0.28f, 0.45f, 0.7f, 1f).forEach { a -> Swatch(p.verdigris.copy(alpha = a)) }
        Spacer(Modifier.width(6.dp))
        Text("Profit", style = Type.label.copy(color = p.inkFaint, fontSize = 11.sp))
    }
}

@Composable
private fun Swatch(c: Color) = Box(Modifier.padding(horizontal = 2.dp).size(12.dp).background(c, RoundedCornerShape(3.dp)))

/** The month in figures: net, winning and losing days, best and worst, averages. */
@Composable
private fun MonthSummary(month: YearMonth, days: Map<LocalDate, DailyPnl.Day>, live: Boolean) {
    val p = LocalPalette.current
    val all = days.values.sortedBy { it.date }
    val net = all.sumOf { it.pnl }
    val wins = all.filter { it.pnl > 0 }
    val losses = all.filter { it.pnl < 0 }
    LedgerCard(title = "${month.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} summary · ${if (live) "Zerodha" else "Paper"}") {
        if (all.isEmpty()) { Note("No trading days recorded this month."); return@LedgerCard }
        Text("Net P&L", style = Type.label.copy(color = p.inkSoft, fontSize = 12.sp))
        Text(rupees(net), style = Type.figureHuge.copy(color = if (net >= 0) p.verdigris else p.oxblood, fontSize = 32.sp))
        Spacer(Modifier.padding(top = 6.dp))
        SummaryLine("Trading days", "${all.size}")
        SummaryLine("Profit days · loss days", "${wins.size} · ${losses.size}")
        SummaryLine("Win rate", "%.0f%%".format(Locale.ENGLISH, 100.0 * wins.size / all.size))
        SummaryLine("Gross profit", rupees(wins.sumOf { it.pnl }), p.verdigris)
        SummaryLine("Gross loss", rupees(losses.sumOf { it.pnl }), p.oxblood)
        all.maxByOrNull { it.pnl }?.takeIf { it.pnl > 0 }?.let { SummaryLine("Best day", "${it.date.dayOfMonth} · ${rupees(it.pnl)}", p.verdigris) }
        all.minByOrNull { it.pnl }?.takeIf { it.pnl < 0 }?.let { SummaryLine("Worst day", "${it.date.dayOfMonth} · ${rupees(it.pnl)}", p.oxblood) }
        SummaryLine("Average per day", rupees(net / all.size))
        all.sumOf { it.trades }.takeIf { it > 0 }?.let { SummaryLine("Trades", "$it") }
        Note("Each day is realised plus open P&L after charges, as it stood at the day's last reading.", Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun SummaryLine(label: String, value: String, color: Color? = null) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Type.bodySmall.copy(color = p.inkSoft), modifier = Modifier.weight(1f))
        Text(value, style = Type.figure.copy(color = color ?: p.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold))
    }
}
