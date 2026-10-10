package com.optionslab.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.optionslab.app.data.RecordedData
import com.optionslab.app.ui.components.BrassButton
import com.optionslab.app.ui.components.LedgerCard
import com.optionslab.app.ui.components.LedgerLine
import com.optionslab.app.ui.components.Note
import com.optionslab.app.ui.components.Sparkline
import com.optionslab.app.ui.components.Stamp
import com.optionslab.app.ui.components.Token
import com.optionslab.app.ui.components.clearOfBars
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import com.optionslab.ira.MarketRecord
import com.optionslab.ira.NewsImpact
import com.optionslab.ira.RecorderStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * "Market data" (opened from the Market recorder card, More, Data & Harvest; Boss, 06 Oct 2026): what the recorder has
 * saved, so Boss sees the data being collected and its first lessons. Pick a recorded day: its headlines with the index's
 * move after each (after the fact), the index futures' OI and basis with the day's OI build-up, the futures' book per 15
 * minutes; across days, FII index-futures long %, GIFT Nifty before the open against NIFTY's open, and from 20 recorded
 * days the first lessons. Read only, information only: nothing here trades, changes a setting or sends anything.
 *
 * A day file is decrypted and parsed off the main thread and kept per day ([RecordedData]); an empty, missing or partly
 * unreadable day says so.
 */
@Composable
fun MarketDataScreen(onClose: () -> Unit) {
    val p = LocalPalette.current
    val sysBars = com.optionslab.app.ui.components.outerBars()
    Dialog(onDismissRequest = onClose, properties = DialogProperties(securePolicy = com.optionslab.app.security.Capture.policy,
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(p.paper).clearOfBars(sysBars)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = "Back" }
                    .clickable(role = Role.Button, onClick = onClose), contentAlignment = Alignment.Center) {
                    Text("‹", style = Type.masthead.copy(color = p.ink, fontSize = 28.sp))
                }
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("Market data", style = Type.title.copy(color = p.ink, fontSize = 17.sp))
                    Text("What the recorder has saved · information only", style = Type.bodySmall.copy(color = p.inkSoft, fontSize = 12.sp))
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(p.rule))
            Box(Modifier.weight(1f)) { MarketDataBody() }
        }
    }
}

/** One day ready to show (computed off the main thread). */
internal data class DayView(
    val date: LocalDate, val day: RecorderStats.Day, val impacts: List<NewsImpact.Impact>,
    val futures: List<RecorderStats.FutureDay>, val depth: Map<String, List<RecorderStats.DepthSlot>>,
) {
    companion object {
        fun of(date: LocalDate, day: RecorderStats.Day) =
            DayView(date, day, NewsImpact.impacts(day.news, day.levels), RecorderStats.futures(day), RecorderStats.depth(day))
    }
}

/** The cross-day views: every day's summary once read ([summaries] null while reading: [read] of [total]). */
internal data class CrossDays(val summaries: List<RecorderStats.Summary>?, val read: Int, val total: Int)

/** The loading around [MarketDataContent]: the day list, the picked day, every day's summary; all off the main thread. */
@Composable
fun MarketDataBody() {
    var days by remember { mutableStateOf<List<Pair<LocalDate, Long>>?>(null) }
    var pick by remember { mutableStateOf<LocalDate?>(null) }
    var view by remember { mutableStateOf<DayView?>(null) }
    var summaries by remember { mutableStateOf<List<RecorderStats.Summary>?>(null) }
    var read by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        val d = withContext(Dispatchers.IO) { RecordedData.days() }
        days = d
        if (pick == null) pick = d.firstOrNull()?.first
    }
    LaunchedEffect(pick, days) {
        val d = pick ?: return@LaunchedEffect
        val size = days?.firstOrNull { it.first == d }?.second ?: return@LaunchedEffect
        if (view?.date != d) view = null
        view = withContext(Dispatchers.IO) { DayView.of(d, RecordedData.day(d, size)) }
    }
    LaunchedEffect(days) {
        val d = days ?: return@LaunchedEffect
        summaries = withContext(Dispatchers.IO) { RecordedData.summaries(d) { n -> read = n } }
    }
    MarketDataContent(days, pick, { pick = it }, view?.takeIf { it.date == pick }, CrossDays(summaries, read, days?.size ?: 0))
}

private val CHIP = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
private val CHIP_YEAR = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
private val SHORT = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

/** A recorded day's chip: "Tue 6 Oct" ("6 Oct 2025" outside the newest day's year). */
internal fun dayChip(d: LocalDate, newest: LocalDate?): String = if (newest == null || d.year == newest.year) d.format(CHIP) else d.format(CHIP_YEAR)

/** The page itself, from what is loaded (the layout tests render it directly). */
@Composable
internal fun MarketDataContent(
    days: List<Pair<LocalDate, Long>>?, pick: LocalDate?, onPick: (LocalDate) -> Unit, view: DayView?, cross: CrossDays,
) {
    var allNews by remember(pick) { mutableStateOf(false) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            LedgerCard(title = "Recorded days") {
                when {
                    days == null -> Note("Reading the list…")
                    days.isEmpty() -> Note("Nothing recorded yet. The recorder writes on trading days from 09:00 to 15:45 while the market watch runs.")
                    else -> {
                        val newest = days.first().first
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(days, key = { it.first.toString() }) { (d, _) -> Token(dayChip(d, newest), d == pick) { onPick(d) } }
                        }
                        days.firstOrNull { it.first == pick }?.let { (_, bytes) -> LedgerLine("File size", MarketRecord.size(bytes)) }
                    }
                }
            }
        }
        if (pick != null) {
            if (view == null) item { LedgerCard { Note("Reading ${pick.format(SHORT)}…") } }
            else dayItems(view, allNews, { allNews = true })
        }
        item { FiiCard(cross) }
        item { GapCard(cross) }
        item { LessonsCard(cross) }
        item { Note("Read only. Nothing on this page trades or changes anything; the files stay encrypted on this phone.", Modifier.padding(horizontal = 4.dp)) }
    }
}

private const val NEWS_FIRST = 30

private fun androidx.compose.foundation.lazy.LazyListScope.dayItems(v: DayView, allNews: Boolean, showAll: () -> Unit) {
    val d = v.day
    if (d.partial) item {
        LedgerCard(title = "Not everything was recorded") {
            if (d.bad > 0) Note("${d.bad} part${if (d.bad == 1) "" else "s"} of this day's file could not be read (cut off by a power loss, or damaged).")
            if (d.gaps.isNotEmpty()) {
                Note("${d.gaps.size} gap${if (d.gaps.size == 1) "" else "s"} recorded: something that could not be read at the time.")
                d.gaps.take(5).forEach { g -> LedgerLine((g.t?.let { MarketRecord.hms(it) } ?: "") + " " + g.what, g.why) }
            }
        }
    }
    if (d.empty) {
        item {
            LedgerCard(title = "Nothing recorded on ${v.date.format(SHORT)}") {
                Note("This day's file holds no market records: the recorder may have been off, the phone asleep, or the day only begun.")
            }
        }
        return
    }
    item { NewsCard(v, allNews, showAll) }
    item { FuturesCard(v) }
    item { DepthCard(v) }
}

private fun agreementColor(p: com.optionslab.app.ui.theme.Palette, a: NewsImpact.Agreement?): Color =
    when (a) { NewsImpact.Agreement.AGREED -> p.verdigris; NewsImpact.Agreement.AGAINST -> p.oxblood; else -> p.inkSoft }

@Composable
private fun NewsCard(v: DayView, all: Boolean, showAll: () -> Unit) {
    val p = LocalPalette.current
    LedgerCard(title = "News timeline") {
        Note(NewsImpact.CAVEAT)
        if (v.impacts.isEmpty()) { Spacer(Modifier.height(6.dp)); Note("No headline recorded this day."); return@LedgerCard }
        val shown = if (all) v.impacts else v.impacts.take(NEWS_FIRST)
        shown.forEach { im ->
            com.optionslab.app.ui.components.Rule(Modifier.padding(vertical = 8.dp))
            val s = im.seen
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(MarketRecord.hms(s.firstSeen), style = Type.figure.copy(color = p.ink, fontSize = 13.sp))
                Spacer(Modifier.width(8.dp))
                Text(s.source, style = Type.bodySmall.copy(color = p.inkSoft), modifier = Modifier.weight(1f))
                Spacer(Modifier.width(6.dp))
                Stamp(s.toneWord.ifBlank { "untoned" }, when (s.direction) { 1 -> p.verdigris; -1 -> p.oxblood; else -> p.inkSoft })
            }
            Text(s.title, style = Type.body.copy(color = p.ink), modifier = Modifier.padding(top = 2.dp))
            if (s.markets.isNotEmpty()) Text("Markets: " + s.markets.joinToString(", "), style = Type.bodySmall.copy(color = p.inkSoft))
            if (im.after.isEmpty()) Note("No index level recorded at that time.")
            for ((ix, a) in im.after) {
                val line = buildAnnotatedString {
                    withStyle(SpanStyle(color = p.ink)) { append("$ix at ${MarketRecord.num(a.base, 1)}:") }
                    if (a.moves.isEmpty()) withStyle(SpanStyle(color = p.inkSoft)) { append(" no later level recorded") }
                    a.moves.forEachIndexed { i, m ->
                        val ag = NewsImpact.agreement(s.direction, m.points)
                        append(if (i == 0) "  " else "  ·  ")
                        withStyle(SpanStyle(color = p.inkSoft)) { append("+${m.minutes} min ") }
                        withStyle(SpanStyle(color = agreementColor(p, ag))) {
                            append(NewsImpact.moveText(m))
                            if (ag == NewsImpact.Agreement.AGREED || ag == NewsImpact.Agreement.AGAINST) append(" " + ag.label)
                        }
                    }
                }
                Text(line, style = Type.figure.copy(fontSize = 13.sp), modifier = Modifier.padding(top = 2.dp))
            }
        }
        if (!all && v.impacts.size > NEWS_FIRST) {
            Spacer(Modifier.height(8.dp))
            BrassButton("Show all ${v.impacts.size} headlines", Modifier.fillMaxWidth(), tone = p.inkSoft, onClick = showAll)
        }
    }
}

private fun pts(x: Double) = (if (x > 0) "+" else "") + MarketRecord.num(x, 1)

private fun signed(x: Long) = (if (x > 0) "+" else "") + x

@Composable
private fun FuturesCard(v: DayView) {
    val p = LocalPalette.current
    LedgerCard(title = "Index futures: OI and basis") {
        if (v.futures.isEmpty()) { Note("No index future recorded this day (they need a Zerodha session)."); return@LedgerCard }
        Note("Through the day, as recorded each minute. Build-up: price up and OI up is long build-up, price down and OI up short build-up, " +
            "price down and OI down long unwinding, price up and OI down short covering.")
        v.futures.forEachIndexed { i, f ->
            com.optionslab.app.ui.components.Rule(Modifier.padding(vertical = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(f.name, style = Type.title.copy(color = p.ink, fontSize = 15.sp), modifier = Modifier.weight(1f))
                f.buildUp?.let { b ->
                    Stamp(b.label, if (b == RecorderStats.BuildUp.LONG_BUILD_UP || b == RecorderStats.BuildUp.SHORT_COVERING) p.verdigris else p.oxblood)
                }
            }
            Note(f.buildUp?.meaning ?: "No build-up reading: price or OI unchanged, or OI not recorded.")
            LedgerLine("Price", "${MarketRecord.num(f.firstPrice, 1)} to ${MarketRecord.num(f.lastPrice, 1)}")
            LedgerLine("Open interest", f.lastOi?.let { o -> "$o" + (f.oiChange?.let { " (${signed(it)})" } ?: "") } ?: "not recorded")
            if (f.oi.size >= 2) Sparkline(RecorderStats.thin(f.oi).map { it.toDouble() }, p.ink, Modifier.fillMaxWidth().height(30.dp))
            LedgerLine("Basis (future less index)", f.lastBasis?.let { MarketRecord.num(it, 1) } ?: "not recorded")
            if (f.basis.size >= 2) Sparkline(RecorderStats.thin(f.basis), p.brass, Modifier.fillMaxWidth().height(30.dp))
            if (i == v.futures.lastIndex) Spacer(Modifier.height(2.dp))
        }
    }
}

@Composable
private fun DepthCard(v: DayView) {
    val p = LocalPalette.current
    LedgerCard(title = "Futures book per 15 minutes") {
        if (v.depth.isEmpty()) { Note("No index future's book recorded this day."); return@LedgerCard }
        Note("Average spread (rupees) and buy/sell imbalance: (buy - sell) / (buy + sell) of the quantities waiting, -1 to +1.")
        for ((name, slots) in v.depth) {
            Text(name, style = Type.title.copy(color = p.ink, fontSize = 15.sp), modifier = Modifier.padding(top = 10.dp))
            slots.forEach { s ->
                val spread = s.avgSpread?.let { String.format(Locale.ENGLISH, "spread %.2f", it) } ?: "spread n/a"
                val imb = s.imbalance?.let { RecorderStats.imbalanceText(it) } ?: "imbalance n/a"
                val x = s.imbalance
                val color = when { x == null -> p.inkSoft; x > 0.005 -> p.verdigris; x < -0.005 -> p.oxblood; else -> p.ink }
                LedgerLine(MarketRecord.hm(s.start), "$spread · $imb", color)
            }
        }
    }
}

@Composable
private fun reading(c: CrossDays) = Note("Reading the recorded days… ${c.read} of ${c.total}")

@Composable
private fun FiiCard(c: CrossDays) {
    val p = LocalPalette.current
    LedgerCard(title = "FII index futures: long %") {
        val s = c.summaries ?: run { reading(c); return@LedgerCard }
        val fii = RecorderStats.fiiLong(s.flatMap { it.participants })
        if (fii.isEmpty()) { Note("No NSE participant-wise OI recorded yet (read after 18:30 on trading days)."); return@LedgerCard }
        Note("FIIs' index-futures longs as a share of their index-futures positions, NSE's participant-wise OI, last ${fii.size} trade date${if (fii.size == 1) "" else "s"}.")
        fii.forEach { f ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(f.date.format(SHORT), style = Type.bodySmall.copy(color = p.inkSoft))
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f).height(12.dp).background(p.chip)) {
                    Box(Modifier.fillMaxWidth((f.longPct / 100).toFloat().coerceIn(0f, 1f)).fillMaxHeight()
                        .background(if (f.longPct >= 50) p.verdigris else p.oxblood))
                }
                Spacer(Modifier.width(8.dp))
                Text(String.format(Locale.ENGLISH, "%.1f%%", f.longPct), style = Type.figure.copy(color = p.ink, fontSize = 13.sp))
            }
        }
    }
}

@Composable
private fun GapCard(c: CrossDays) {
    LedgerCard(title = "GIFT Nifty before the open vs NIFTY's open") {
        val s = c.summaries ?: run { reading(c); return@LedgerCard }
        val gaps = RecorderStats.gaps(s).takeLast(10).reversed()
        if (gaps.isEmpty()) { Note("No day yet with both a GIFT Nifty reading before 09:15 and NIFTY's open recorded."); return@LedgerCard }
        Note("The last GIFT Nifty reading before 09:15 against NIFTY's first recorded level from 09:15, in points. GIFT Nifty is a future: part of the difference is its premium.")
        gaps.forEach { g ->
            LedgerLine(g.date.format(SHORT), "GIFT ${MarketRecord.num(g.gift, 1)} · open ${MarketRecord.num(g.open, 1)} · off ${pts(g.error)}")
            val said = g.predictedGap
            val was = g.actualGap
            if (said != null && was != null) Note("Gap from the last recorded close: GIFT pointed to ${pts(said)}, it opened ${pts(was)}.")
        }
    }
}

@Composable
private fun LessonsCard(c: CrossDays) {
    LedgerCard(title = "First lessons") {
        val s = c.summaries ?: run { reading(c); return@LedgerCard }
        val l = RecorderStats.lessons(s)
        if (l == null) Note(RecorderStats.waiting(RecorderStats.recordedDays(s)))
        else l.lines().forEach { (k, v) -> LedgerLine(k, v) }
        Spacer(Modifier.height(4.dp))
        Note(RecorderStats.LESSON_CAVEAT)
    }
}
