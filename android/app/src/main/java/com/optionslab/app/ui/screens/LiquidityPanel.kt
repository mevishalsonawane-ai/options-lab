package com.optionslab.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.optionslab.app.ui.components.Token
import com.optionslab.app.ui.components.axisFontSize
import com.optionslab.app.ui.components.measureLine
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import com.optionslab.engine.Upstox
import com.optionslab.engine.orb.Bar
import com.optionslab.engine.orb.LiquidityOverlay
import com.optionslab.engine.orb.LiquidityRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Where the chart's Liquidity 15+5 layer gets the arm's trades and the time (the app: [ArmLiquiditySource]; a fake in tests).
 * The candles are the chart's own (see [LiquidityCache]).
 */
internal interface LiquiditySource {
    /** Today's Liquidity 15+5 trades on [underlying], open and closed. */
    suspend fun trades(underlying: String): List<LiquidityOverlay.Trade>
    fun now(): LocalDateTime
    /** The book's closed paper position behind [t] (a tapped marker's replay); null: none found (the marker's card instead). */
    suspend fun position(t: LiquidityOverlay.Trade): com.optionslab.app.data.OrbArms.Position? = null
    /** [p]'s replay ([LiquidityReplayData] in the app); null: none. Reads only. */
    suspend fun replay(p: com.optionslab.app.data.OrbArms.Position): com.optionslab.ira.LiquidityReplay.Replay? = null
}

/** The app's: the arm's own book ([com.optionslab.app.data.OrbArms.view]) and the market clock. Reads only. */
internal object ArmLiquiditySource : LiquiditySource {
    override suspend fun trades(underlying: String): List<LiquidityOverlay.Trade> {
        val view = runCatching { com.optionslab.app.data.OrbArms.view() }.getOrNull() ?: return emptyList()
        val row = view.arms.firstOrNull { it.arm.source == LiquidityRules.ARM.source } ?: return emptyList()
        return row.today.mapNotNull { tradeOf(it, underlying) }
    }

    override fun now(): LocalDateTime = com.optionslab.app.data.Market.now().toLocalDateTime()

    /** A copy of the day's Liquidity positions (the lock held only to copy), read off the main thread and given up after 2 s. */
    override suspend fun position(t: LiquidityOverlay.Trade): com.optionslab.app.data.OrbArms.Position? {
        if (!t.replayable || com.optionslab.app.BuildConfig.GOLD) return null
        val day = t.entryTime.toLocalDate()
        val list = withTimeoutOrNull(2_000) {
            withContext(Dispatchers.IO) { runCatching { com.optionslab.app.data.OrbArms.liquidityTodayWithLive(day) }.getOrNull() }
        } ?: return null
        return positionOf(list, t)
    }

    override suspend fun replay(p: com.optionslab.app.data.OrbArms.Position): com.optionslab.ira.LiquidityReplay.Replay? = LiquidityReplayData.load(p)

    /** The closed paper position [t] was drawn from: the same book, entry time and (when known) symbol; null when none is. */
    fun positionOf(list: List<com.optionslab.app.data.OrbArms.Position>, t: LiquidityOverlay.Trade): com.optionslab.app.data.OrbArms.Position? =
        list.lastOrNull { !it.open && !it.live && it.arm == t.book && it.entryTime == t.entryTime && (t.symbol == null || it.symbol == t.symbol) }

    /** A liquidity book's position as the chart shows it, or null when it is another arm's or another index's. */
    fun tradeOf(p: com.optionslab.app.data.OrbArms.Position, underlying: String): LiquidityOverlay.Trade? {
        val arm = LiquidityRules.BOOKS.firstOrNull { it.source == p.arm } ?: return null
        if (LiquidityRules.underlyingOf(arm) != underlying) return null
        val c = runCatching { com.optionslab.app.data.Paper.contractOf(p.symbol) }.getOrNull()
        val strike = c?.let { "${String.format(Locale.ENGLISH, "%.0f", it.strike)} ${p.right}" } ?: p.symbol
        return LiquidityOverlay.Trade(p.arm, LiquidityRules.minutesOf(arm), if (p.right == "CE") 1 else -1, p.signalBar, p.entryTime,
            p.entry, p.qty + p.sold, strike, p.exit, p.exitTime, p.why, p.level, p.target, p.grossPnl?.let { it - p.charges },
            p.near, p.volSkip, p.strong, live = p.live, symbol = p.symbol)
    }
}

/**
 * The 5-minute candles of the charted index the layer reads, kept from what the chart already loaded: the first full read
 * (the arm's ten days), then every 5-minute batch the chart page fetches for itself ([Bridge.bars]) merged in. Only bars
 * that had closed when they were received are kept (a forming bar's high, low and close are not final). A new read is
 * made only when the bar that closed last is missing (another interval on screen, or the basic chart).
 */
internal class LiquidityCache {
    private var symbol: String? = null
    private var bars: List<Upstox.Bar> = emptyList()
    private var full = false

    /** [list] as received at [now] (IST): a bar whose end is after [now] was still forming and is left out. */
    @Synchronized fun offer(sym: String, list: List<Upstox.Bar>, now: LocalDateTime, history: Boolean = false) {
        val s = sym.uppercase()
        if (s != symbol) { symbol = s; bars = emptyList(); full = false }
        val at = epoch(now)
        val closed = list.filter { it.epochSecond + BAR_SECONDS <= at }
        if (closed.isNotEmpty()) {
            val m = HashMap<Long, Upstox.Bar>(bars.size + closed.size)
            bars.forEach { m[it.epochSecond] = it }
            closed.forEach { m[it.epochSecond] = it }
            bars = m.values.sortedBy { it.epochSecond }.takeLast(MAX_BARS)
        }
        if (history) full = true
    }

    /**
     * The candles held for [sym] when they include the last 5-minute bar closed by [now] (received after it closed: see
     * [offer]); after 15:30 on a day that traded, the 15:25 bar. Null: read them again.
     */
    @Synchronized fun fresh(sym: String, now: LocalDateTime): List<Upstox.Bar>? {
        if (sym.uppercase() != symbol || !full || bars.isEmpty()) return null
        val need = lastClosedStart(now)
            ?: now.toLocalDate().atTime(15, 25).takeIf { now.toLocalTime() >= LocalTime.of(15, 30) && tradedOn(now) }
            ?: return bars
        val at = epoch(need)
        return bars.takeIf { b -> b.any { it.epochSecond == at } }
    }

    /** A bar of [now]'s day is held (the day traded; a holiday or weekend has none). */
    private fun tradedOn(now: LocalDateTime): Boolean {
        val from = epoch(now.toLocalDate().atStartOfDay())
        return bars.any { it.epochSecond >= from && it.epochSecond < from + 86_400 }
    }

    companion object {
        const val MAX_BARS = 1_500
        private const val BAR_SECONDS = 300L

        private fun epoch(t: LocalDateTime) = t.atZone(com.optionslab.engine.IST).toEpochSecond()

        /** The start of the 5-minute bar that closed last, during a session (09:20-15:30); null outside it. */
        fun lastClosedStart(now: LocalDateTime): LocalDateTime? {
            val t = now.toLocalTime()
            if (t.isBefore(LocalTime.of(9, 20)) || t.isAfter(LocalTime.of(15, 30))) return null
            val m = (t.hour * 60 + t.minute - (9 * 60 + 15)) / 5 * 5 - 5
            return now.toLocalDate().atTime(9, 15).plusMinutes(m.toLong())
        }

        /** Only the bars closed by [now] (a forming bar would change the input, and so the model, on every read). */
        fun closed(bars: List<Bar>, now: LocalDateTime): List<Bar> = bars.filter { !it.start.plusMinutes(5).isAfter(now) }

        /** The feed's candles as the arm's bars (IST, session minutes only). */
        fun toBars(list: List<Upstox.Bar>): List<Bar> = list.map {
            Bar(Instant.ofEpochSecond(it.epochSecond).atZone(com.optionslab.engine.IST).toLocalDateTime(), it.open, it.high, it.low, it.close)
        }.filter { val m = it.start.hour * 60 + it.start.minute; m in (9 * 60 + 15)..(15 * 60 + 29) }
    }
}

/**
 * The Liquidity 15+5 layer under (or beside) the chart of BANKNIFTY, FINNIFTY or MIDCPNIFTY: its books' charts, the swing zones and
 * pools as the arm sees them on the closed bars, the arm's trades today and the breaks it skipped for want of room.
 * [bars]: the index's 5-minute candles (cached; see [LiquidityCache]). Reads every 30 s while [visible]; the levels are
 * worked out again only when a bar has closed or the trades changed.
 */
@Composable
internal fun LiquidityPanel(underlying: String, visible: Boolean, bars: suspend (LocalDateTime) -> List<Upstox.Bar>,
                            source: LiquiditySource, modifier: Modifier = Modifier,
                            /** The app's price alarms a tapped level's sheet sets and lists ([LevelSheet]); null: none. */
                            levelAlarms: LevelAlarms? = null,
                            /** The index's last traded price (the chart's 1-minute feed); null: not known. */
                            lastPrice: suspend () -> Double? = { null }) {
    val tfs = remember(underlying) { LiquidityOverlay.timeframes(underlying) }
    var tf by remember(underlying) { mutableIntStateOf(tfs.first()) }
    var input by remember(underlying) { mutableStateOf<List<Bar>?>(null) }
    var trades by remember(underlying) { mutableStateOf<List<LiquidityOverlay.Trade>>(emptyList()) }
    var closedAt by remember(underlying) { mutableStateOf<LocalDateTime?>(null) }
    var error by remember(underlying) { mutableStateOf<String?>(null) }
    // The bars, not words: a string with "now" in it would change on every poll and redraw the chart each time.
    var behind by remember(underlying) { mutableStateOf<Behind?>(null) }
    var model by remember(underlying, tf) { mutableStateOf<LiquidityOverlay.Model?>(null) }

    LaunchedEffect(underlying, visible) {
        if (!visible) return@LaunchedEffect
        while (true) {
            val now = source.now()
            withContext(Dispatchers.IO) { runCatching { bars(now) } }
                .onSuccess { got ->
                    // The closed bars only: a forming bar changes on every read and would rebuild the model each poll.
                    val b = LiquidityCache.closed(LiquidityCache.toBars(got), now)
                    if (b != input) input = b
                    error = null
                    val b2 = behindAt(b, now)
                    if (b2 != behind) behind = b2
                }
                .onFailure { if (input == null) error = "Could not load the $underlying candles: ${it.message ?: "no data"}" }
            val t = withContext(Dispatchers.IO) { runCatching { source.trades(underlying) }.getOrNull() }
            if (t != null && t != trades) trades = t
            // The bar that closed last decides what is shown: a new one (or the trades) is all that recomputes.
            // Outside a session nothing closes: one value until the next session (not a recompute every minute).
            val closed = LiquidityCache.lastClosedStart(now)?.plusMinutes(5)
                ?: now.toLocalDate().atTime(if (now.toLocalTime().isBefore(LocalTime.of(9, 20))) LocalTime.MIDNIGHT else LocalTime.of(15, 30))
            if (closed != closedAt) closedAt = closed
            delay(30_000)
        }
    }
    LaunchedEffect(input, trades, tf, closedAt) {
        val b = input ?: return@LaunchedEffect
        if (closedAt == null) return@LaunchedEffect
        val now = source.now()
        val m = withContext(Dispatchers.Default) { LiquidityOverlay.build(b, tf, underlying, now, trades) }
        if (m != model) model = m
    }
    LiquidityChart(underlying, tfs, tf, { tf = it }, model, error, modifier, behind?.let(::behindText),
        levelAlarms = levelAlarms, lastPrice = lastPrice, today = source.now().toLocalDate(),
        positionOf = { source.position(it) }, replay = { source.replay(it) })
}

/** The candles behind: the newest closed 5-minute bar held ([last], its start; null: none) and the one due ([due], its start). */
internal data class Behind(val last: LocalDateTime?, val due: LocalDateTime)

/**
 * When the newest closed 5-minute candle held is more than one bar older than the last one closed by [now] (in a
 * session): what the layer says about it; null when it is current (or outside a session).
 */
internal fun behindBy(bars: List<Bar>, now: LocalDateTime): String? = behindAt(bars, now)?.let(::behindText)

/** [behindBy] as bar times (what the panel keeps: it changes only when a bar does). */
internal fun behindAt(bars: List<Bar>, now: LocalDateTime): Behind? {
    val need = LiquidityCache.lastClosedStart(now) ?: return null
    val last = bars.lastOrNull()?.start ?: return Behind(null, need)
    // One bar behind is a read in flight; at 09:20 the bar before the day's first is the last session's.
    val ok = if (need.toLocalTime() == LocalTime.of(9, 15)) true else !last.isBefore(need.minusMinutes(5))
    return if (ok) null else Behind(last, need)
}

/** [b] in words, formatted when shown. */
internal fun behindText(b: Behind): String = b.last?.let {
    "Candles behind: the last closed ${hhmm(it.plusMinutes(5))}, the ${hhmm(b.due.plusMinutes(5))} close is missing"
} ?: "No candles yet"

/** The layer from its state (what [LiquidityPanel] shows; tests drive it with a model built from fixed bars). */
@Composable
internal fun LiquidityChart(
    underlying: String, timeframes: List<Int>, tf: Int, onTf: (Int) -> Unit,
    model: LiquidityOverlay.Model?, error: String?, modifier: Modifier = Modifier,
    /** The candles are more than a bar behind ([behindBy]): said in a chip over the levels. */
    behind: String? = null,
    /** The app's price alarms a tapped level's sheet sets and lists; null: the sheet has no alarm button. */
    levelAlarms: LevelAlarms? = null,
    /** The index's last traded price for a tapped level's sheet; null (or no answer): the last closed bar's close. */
    lastPrice: suspend () -> Double? = { null },
    /** The session's day ("taken today"); null: the last bar's. */
    today: java.time.LocalDate? = null,
    /**
     * A tapped closed paper trade's book position ([LiquiditySource.position]): found, the tap opens its replay; null here
     * (or none found), the trade's card. Open and live trades, and skipped breaks, always show their card.
     */
    positionOf: (suspend (LiquidityOverlay.Trade) -> com.optionslab.app.data.OrbArms.Position?)? = null,
    /** The replay of the position [positionOf] found ([LiquidityReplayData] in the app). Reads only. */
    replay: suspend (com.optionslab.app.data.OrbArms.Position) -> com.optionslab.ira.LiquidityReplay.Replay? = { null },
) {
    val p = LocalPalette.current
    var picked by remember(tf) { mutableStateOf<LiquidityOverlay.Marker?>(null) }
    // A closed paper trade's marker tapped: its position being looked up, then its replay (never in the GOLD build).
    var resolving by remember(tf) { mutableStateOf<LiquidityOverlay.Marker?>(null) }
    var replaying by remember(tf) { mutableStateOf<com.optionslab.app.data.OrbArms.Position?>(null) }
    val pick: (LiquidityOverlay.Marker) -> Unit = { mk ->
        if (positionOf != null && mk.trade?.replayable == true && !com.optionslab.app.BuildConfig.GOLD) resolving = mk else picked = mk
    }
    var level by remember(tf) { mutableStateOf<PickedLevel?>(null) }
    var levelLast by remember(tf) { mutableStateOf<Double?>(null) }
    Column(modifier.background(p.paper)) {
        Row(Modifier.fillMaxWidth().background(p.paperDeep).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Liquidity 15+5 · $underlying", maxLines = 1, softWrap = false,
                style = Type.label.copy(color = p.ink, fontSize = 12.sp, fontWeight = FontWeight.Bold), modifier = Modifier.padding(end = 4.dp))
            timeframes.forEach { m -> Token("${m}m", m == tf) { onTf(m) } }
            // As of the close of the last bar drawn.
            val last = model?.lastBar
            if (model != null && last != null) Text("as of ${hhmm(last.plusMinutes(model.minutes.toLong()))}", maxLines = 1, softWrap = false,
                style = Type.label.copy(color = p.inkSoft, fontSize = 12.sp), modifier = Modifier.padding(start = 4.dp))
        }
        behind?.let {
            Text(it, style = Type.label.copy(color = p.oxblood, fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp).background(p.chip, RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 6.dp).testTag("liquidity-behind"))
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val m = model
            if (m == null) Text(error ?: "Loading the liquidity levels…", style = Type.bodySmall.copy(color = p.inkSoft),
                modifier = Modifier.align(Alignment.Center).padding(12.dp))
            else if (m.bars.isEmpty()) Text("No closed ${tf}-minute bars yet.", style = Type.bodySmall.copy(color = p.inkSoft),
                modifier = Modifier.align(Alignment.Center).padding(12.dp))
            else LevelsCanvas(m, onLevel = { s -> levelLast = null; level = pickedOf(s, m) }) { pick(it) }
        }
        // Every marker as a chip too (newest first): a tap opens its card, as a tap on the chart does.
        val marks = model?.markers.orEmpty().sortedByDescending { it.bar }
        if (model != null && marks.isNotEmpty()) Row(Modifier.fillMaxWidth().background(p.paperDeep).horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            marks.forEach { mk ->
                Box(Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).clickable(role = Role.Button) { pick(mk) }, contentAlignment = Alignment.Center) {
                    Text(chipText(mk, model), maxLines = 1, softWrap = false,
                        style = Type.label.copy(color = markColor(mk, p), fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.background(p.chip, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 6.dp))
                }
            }
        }
    }
    // A level's last price: the chart's own when it answers, else the last closed bar's close.
    LaunchedEffect(level) {
        val l = level ?: return@LaunchedEffect
        val got = withContext(Dispatchers.IO) { runCatching { lastPrice() }.getOrNull() }
        levelLast = got ?: l.lastClose
    }
    LaunchedEffect(resolving) {
        val mk = resolving ?: return@LaunchedEffect
        val t = mk.trade ?: return@LaunchedEffect
        val found = try {
            // The timeout runs on IO's real clock (inside a LaunchedEffect a Compose test's clock is virtual).
            withContext(Dispatchers.IO) { withTimeoutOrNull(3_000) { positionOf?.invoke(t) } }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
        // Back on the main thread before the state that opens a dialog changes (a dialog needs a Looper; under a test
        // dispatcher the effect could otherwise resume on the IO worker and compose the dialog there).
        withContext(Dispatchers.Main.immediate) {
            resolving = null
            if (found != null) replaying = found else picked = mk
        }
    }
    replaying?.let { pos -> LiquidityReplayDialog(pos, replay) { replaying = null } }
    level?.let { l ->
        LevelSheet(underlying, l, tf, today ?: model?.lastBar?.toLocalDate() ?: java.time.LocalDate.now(com.optionslab.engine.IST), levelLast,
            levelAlarms) { level = null }
    }
    picked?.let { mk ->
        val lines = mk.trade?.let { LiquidityOverlay.card(it) } ?: mk.skip?.let { LiquidityOverlay.card(it) } ?: emptyList()
        com.optionslab.app.ui.components.AlertDialog(
            onDismissRequest = { picked = null },
            properties = androidx.compose.ui.window.DialogProperties(securePolicy = com.optionslab.app.security.Capture.policy),
            title = { Text(if (mk.kind == LiquidityOverlay.MarkerKind.NO_ROOM) "Skipped break" else "Liquidity trade", style = Type.title) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()).testTag("liquidity-card")) {
                    lines.forEach { Text(it, style = Type.bodySmall.copy(color = p.ink), modifier = Modifier.padding(vertical = 2.dp)) }
                }
            },
            confirmButton = { com.optionslab.app.ui.components.TextButton({ picked = null }) { Text("Close") } },
        )
    }
}

private fun hhmm(t: LocalDateTime) = String.format(Locale.ENGLISH, "%02d:%02d", t.hour, t.minute)

/** A marker's chip: its shape, tag and the bar's time. */
internal fun chipText(mk: LiquidityOverlay.Marker, model: LiquidityOverlay.Model): String {
    val at = model.bars.getOrNull(mk.bar)?.start?.let { hhmm(it) } ?: ""
    return when (mk.kind) {
        LiquidityOverlay.MarkerKind.ENTRY -> "${if (mk.side > 0) "▲" else "▼"} ${mk.text} $at"
        LiquidityOverlay.MarkerKind.EXIT -> "■ $at ${mk.text}"
        LiquidityOverlay.MarkerKind.NO_ROOM -> "${if (mk.side > 0) "△" else "▽"} skipped $at"
    }
}

private fun markColor(mk: LiquidityOverlay.Marker, p: com.optionslab.app.ui.theme.Palette): Color = when (mk.kind) {
    LiquidityOverlay.MarkerKind.ENTRY -> if (mk.side > 0) p.verdigris else p.oxblood
    LiquidityOverlay.MarkerKind.EXIT -> p.ink
    LiquidityOverlay.MarkerKind.NO_ROOM -> p.inkSoft
}

/**
 * The candles, levels, lines and markers; a tap near a marker picks it, else a tap (or a long press) on a level, or on its
 * label at the right, opens that level ([onLevel]).
 */
@Composable
private fun LevelsCanvas(model: LiquidityOverlay.Model, onLevel: (LiquidityOverlay.Shape) -> Unit = {}, onPick: (LiquidityOverlay.Marker) -> Unit) {
    val p = LocalPalette.current
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = remember(p, density.fontScale) { TextStyle(color = p.inkSoft, fontSize = axisFontSize(9.sp, density.fontScale), fontFeatureSettings = "tnum") }
    val bars = model.bars
    Canvas(Modifier.fillMaxSize().semantics { contentDescription = "Liquidity levels chart" }
        .pointerInput(model) {
            // The level drawn within 12 dp of a touch: over the bars it spans, or beside its label in the right column.
            fun levelAt(pos: Offset): LiquidityOverlay.Shape? {
                val g = Geometry.of(bars.size, size.width.toFloat(), 3.dp.toPx(), 52.dp.toPx())
                val (lo, hi) = priceRange(bars, g.start)
                val h = size.height.toDouble()
                val shapes = model.shapes.filter { it.to >= g.start }
                val price = com.optionslab.ira.LevelAlarm.priceAt(pos.y.toDouble(), lo, hi, h)
                val tol = com.optionslab.ira.LevelAlarm.tolerance(12.dp.toPx().toDouble(), lo, hi, h)
                val levels = shapes.map(::levelOf)
                val i = if (pos.x > g.plotW) com.optionslab.ira.LevelAlarm.hit(levels, price, tol, activeOnly = true)
                    else com.optionslab.ira.LevelAlarm.hit(levels, price, tol, bar = g.start + (pos.x / g.w).toInt())
                return i?.let { shapes[it] }
            }
            detectTapGestures(onLongPress = { pos -> levelAt(pos)?.let(onLevel) }) { pos ->
                val g = Geometry.of(bars.size, size.width.toFloat(), 3.dp.toPx(), 52.dp.toPx())
                val i = g.start + ((pos.x / g.w).toInt())
                val mk = if (pos.x <= g.plotW) LiquidityOverlay.markerNear(model.markers, i) else null
                if (mk != null) onPick(mk) else levelAt(pos)?.let(onLevel)
            }
        }) {
        val g = Geometry.of(bars.size, size.width, 3.dp.toPx(), 52.dp.toPx())
        val view = bars.subList(g.start, bars.size)
        val (lo, hi) = priceRange(bars, g.start)
        val plotH = size.height
        fun y(v: Double) = (plotH * (1 - (v - lo) / (hi - lo))).toFloat()
        fun x(i: Int) = (i - g.start) * g.w
        val upC = p.verdigris; val dnC = p.oxblood
        clipRect(0f, 0f, g.plotW, plotH) {
            // Levels first, under the candles: bands for swings, dashed lines for pools; faded once taken.
            for (s in model.shapes) {
                if (s.to < g.start) continue
                val c = if (s.side > 0) dnC else upC
                val l = x(max(s.from, g.start)); val r = x(s.to) + g.w
                if (s.kind == LiquidityOverlay.Kind.SWING) {
                    val t = y(s.top); val b = y(s.bottom)
                    drawRect(c.copy(alpha = if (s.taken) 0.07f else 0.20f), Offset(l, t), Size(r - l, max(1f, b - t)))
                } else drawLine(c.copy(alpha = if (s.taken) 0.35f else 0.9f), Offset(l, y(s.edge)), Offset(r, y(s.edge)), 1.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
            }
            for (b in view.indices) {
                val bar = view[b]; val cx = b * g.w + g.w / 2
                val c = (if (bar.close >= bar.open) upC else dnC).copy(alpha = 0.75f)
                drawLine(c, Offset(cx, y(bar.high)), Offset(cx, y(bar.low)), 1f)
                val top = y(max(bar.open, bar.close)); val bot = y(min(bar.open, bar.close))
                drawRect(c, Offset(cx - g.w * 0.35f, top), Size(g.w * 0.7f, max(1f, bot - top)))
            }
            // The trades' broken level (solid) and, while open, the target (dotted).
            for (ln in model.lines) {
                if (ln.to < g.start) continue
                drawLine(if (ln.dotted) p.brass else p.gold, Offset(x(max(ln.from, g.start)), y(ln.price)), Offset(x(ln.to) + g.w, y(ln.price)),
                    if (ln.dotted) 1.5f else 2f, pathEffect = if (ln.dotted) PathEffect.dashPathEffect(floatArrayOf(2f, 5f)) else null)
            }
            val r = max(4.dp.toPx(), g.w * 0.6f)
            for (mk in model.markers) {
                if (mk.bar < g.start) continue
                val bar = bars[mk.bar]; val cx = x(mk.bar) + g.w / 2
                val cy = if (mk.above) y(bar.high) - r * 1.6f else y(bar.low) + r * 1.6f
                val pointsUp = if (mk.kind == LiquidityOverlay.MarkerKind.EXIT) !mk.above else mk.side > 0
                val c = markColor(mk, p)
                if (mk.kind == LiquidityOverlay.MarkerKind.EXIT) {
                    drawRect(c, Offset(cx - r * 0.8f, cy - r * 0.8f), Size(r * 1.6f, r * 1.6f))
                } else {
                    val tri = Path().apply {
                        if (pointsUp) { moveTo(cx, cy - r); lineTo(cx + r, cy + r); lineTo(cx - r, cy + r) }
                        else { moveTo(cx, cy + r); lineTo(cx + r, cy - r); lineTo(cx - r, cy - r) }
                        close()
                    }
                    if (mk.kind == LiquidityOverlay.MarkerKind.NO_ROOM) drawPath(tri, c, style = Stroke(1.5f)) else drawPath(tri, c)
                }
                if (mk.kind == LiquidityOverlay.MarkerKind.ENTRY) {
                    val t = measurer.measureLine(mk.text, style.copy(color = c, fontWeight = FontWeight.Bold))
                    val ty = if (mk.above) cy - r - t.size.height else cy + r
                    drawText(t, topLeft = Offset((cx - t.size.width / 2f).coerceIn(0f, max(0f, g.plotW - t.size.width)),
                        ty.coerceIn(0f, max(0f, plotH - t.size.height))))
                }
            }
        }
        // "swing" / "pool" beside the levels still active, in the gutter on the right (one label per line of text).
        var lastTop = Float.NEGATIVE_INFINITY
        model.shapes.filter { !it.taken && it.edge in lo..hi }.sortedBy { y(it.edge) }.forEach { s ->
            val t = measurer.measureLine(s.label, style.copy(color = if (s.side > 0) dnC else upC))
            val top = (y(s.edge) - t.size.height / 2f).coerceIn(0f, max(0f, plotH - t.size.height))
            if (top < lastTop + t.size.height) return@forEach
            drawText(t, topLeft = Offset(g.plotW + 4.dp.toPx(), top))
            lastTop = top
        }
    }
}

/** The prices the plot shows: the visible bars' low to high, 8% spare each side. */
private fun priceRange(bars: List<Bar>, start: Int): Pair<Double, Double> {
    val view = bars.subList(start, bars.size)
    val lo = view.minOf { it.low }; val hi = view.maxOf { it.high }
    val pad = max(hi - lo, 1.0) * 0.08
    return (lo - pad) to (hi + pad)
}

/** How many of the window's bars fit at no less than [minW] px each beside a [gutter] px label column. */
private class Geometry(val start: Int, val w: Float, val plotW: Float) {
    companion object {
        fun of(n: Int, width: Float, minW: Float, gutter: Float): Geometry {
            val plotW = max(1f, width - gutter)
            val w = max(minW, plotW / max(1, n))
            val fit = max(1, (plotW / w).toInt())
            return Geometry(max(0, n - fit), w, plotW)
        }
    }
}
