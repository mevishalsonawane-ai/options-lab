package com.optionslab.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.optionslab.app.data.Market
import com.optionslab.app.ui.AppModel
import com.optionslab.app.ui.Load
import com.optionslab.app.ui.Page
import com.optionslab.app.ui.components.LedgerCard
import com.optionslab.app.ui.components.Note
import com.optionslab.app.ui.components.PriceChart
import com.optionslab.app.ui.components.Rule
import com.optionslab.app.ui.components.Token
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import kotlinx.coroutines.delay
import java.text.NumberFormat
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

private val INR: NumberFormat = NumberFormat.getCurrencyInstance(Locale("en", "IN")).apply { maximumFractionDigits = 0; minimumFractionDigits = 0 }
private val PX: NumberFormat = NumberFormat.getNumberInstance(Locale("en", "IN")).apply { maximumFractionDigits = 2; minimumFractionDigits = 2 }

/** Rupees in Indian grouping: ₹6,57,400 (with a sign when asked). */
private fun inr(x: Double, sign: Boolean = false): String =
    (if (sign && x > 0) "+" else if (x < 0) "−" else "") + INR.format(abs(x))

/** One line of the Home "Live orders" list: an open position or a working order. */
private data class HomeOrder(val name: String, val detail: String, val value: String, val valueColor: Color?, val status: String, val target: RowTarget,
                             /** Who placed the order / opened the position ([com.optionslab.app.data.Origins]). */
                             val source: Pair<String, Boolean>? = null)

/** What Home shows about the money, from the paper account or from Zerodha. */
private data class Money(val pnlToday: Double?, val unused: Double?, val used: Double?) {
    val capital: Double? get() = if (unused != null && used != null) unused + used else null
}

/**
 * Home: the BANKNIFTY chart, today's P&L, unused and used money, and the
 * orders that are live right now. In PAPER mode every figure is the paper
 * account; in LIVE mode, Zerodha.
 */
@Composable
fun AlmanacScreen(model: AppModel, onGo: (String) -> Unit) {
    val s by model.settings.collectAsState(kotlinx.coroutines.Dispatchers.Main.immediate)
    val quotes by model.quotes.collectAsState(kotlinx.coroutines.Dispatchers.Main.immediate)
    val note by model.quoteNote.collectAsState(kotlinx.coroutines.Dispatchers.Main.immediate)
    val daily by model.bankNiftyDaily.collectAsState(kotlinx.coroutines.Dispatchers.Main.immediate)
    val account by model.account.collectAsState(kotlinx.coroutines.Dispatchers.Main.immediate)
    val paper by model.paper.collectAsState(kotlinx.coroutines.Dispatchers.Main.immediate)
    val owners by model.orderOwners.collectAsState(kotlinx.coroutines.Dispatchers.Main.immediate)

    // Prices poll only while Home is on screen and the app is in front.
    com.optionslab.app.ui.PollWhileStarted {
        model.startQuotes()
        try { kotlinx.coroutines.awaitCancellation() } finally { model.stopQuotes() }
    }
    // The money and the orders refresh every 20 s while Home is open; the paper account as fast as its prices move.
    com.optionslab.app.ui.PollWhileStarted(s.live) {
        var last = 0L
        while (true) {
            val now = System.currentTimeMillis()
            if (s.live) { if (com.optionslab.app.data.Broker.loggedIn) model.loadAccount(quiet = true) } else model.loadPaper(quiet = true)
            if (now - last >= 20_000) { model.refreshStrategies(); last = now }
            delay(if (s.live) 20_000 else model.paperRefreshMs().coerceAtMost(20_000))
        }
    }
    LaunchedEffect(s.live) { model.loadBankNiftyDaily() }
    AlmanacContent(s.live, com.optionslab.app.data.Broker.loggedIn, quotes, note, daily, account, paper, onGo,
        onRow = { model.rowAction.value = it }, owners = owners, strategies = { StrategyArmCard(model) { onGo("strategy") } })
}

/**
 * Home from plain state and callbacks (what [AlmanacScreen] shows; tests drive it without an [AppModel]).
 * [loggedIn]: a Zerodha session for today; [strategies]: the strategy card between the money and the chart.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun AlmanacContent(
    live: Boolean,
    loggedIn: Boolean,
    quotes: Map<String, Market.Quote>,
    note: String?,
    daily: List<Pair<java.time.LocalDate, Double>>,
    account: Load<com.optionslab.app.ui.Account>,
    paper: Load<com.optionslab.app.data.Paper.Snapshot>,
    onGo: (String) -> Unit,
    onRow: (RowTarget) -> Unit,
    owners: Map<String, String> = emptyMap(),
    strategies: @Composable () -> Unit,
) {
    val p = LocalPalette.current
    var range by rememberSaveable { mutableStateOf("1D") }

    val money: Money
    val orders: List<HomeOrder>
    val moneyNote: String?
    if (live) {
        val a = (account as? Load.Done)?.value
        money = Money(a?.book?.m2m, a?.funds?.available, a?.funds?.used)
        orders = a?.let { acc ->
            acc.positions.filter { it.qty != 0 }.map {
                HomeOrder(it.symbol, "${if (it.qty < 0) "SELL" else "BUY"} ${abs(it.qty)} · avg ${PX.format(it.avg)} · LTP ${PX.format(it.last)}",
                    inr(it.pnl, true), if (it.pnl >= 0) p.verdigris else p.oxblood, "OPEN", RowTarget.LivePosition(it),
                    com.optionslab.app.data.Origins.livePosition(owners, acc.trades, acc.orders, it.symbol, it.product, it.qty)?.let(com.optionslab.app.data.Origins::positionDisplay))
            } + acc.orders.filter { it.working }.map {
                HomeOrder(it.symbol, "${it.side} ${it.pending.takeIf { n -> n > 0 } ?: it.qty} · ${it.type.lowercase()}${if (it.trigger > 0) " · trigger ${PX.format(it.trigger)}" else ""}",
                    "₹" + PX.format(if (it.price > 0) it.price else it.trigger), null, if (it.status == "TRIGGER PENDING") "TRIGGER PENDING" else "PENDING",
                    RowTarget.LiveOrder(it), orderSource(owners, "kite:${it.id}", it.tag))
            }
        } ?: emptyList()
        moneyNote = when {
            !loggedIn -> "Log in to Zerodha for today to see your money and orders."
            account is Load.Failed -> (account as Load.Failed).why
            else -> null
        }
    } else {
        val v = (paper as? Load.Done)?.value
        money = Money(v?.dayPnl, v?.funds?.availableCash, v?.funds?.utilisedDebits)
        orders = v?.let { snap ->
            snap.positions.positions.filter { it.quantity != 0 }.map {
                HomeOrder(it.symbol, "${if (it.quantity < 0) "SELL" else "BUY"} ${abs(it.quantity)} · avg ${PX.format(it.averagePrice)} · LTP ${PX.format(it.ltp)}",
                    inr(it.pnl, true), if (it.pnl >= 0) p.verdigris else p.oxblood, "OPEN", RowTarget.PaperPosition(it),
                    com.optionslab.app.data.Origins.paperPosition(owners, snap.trades, it.symbol, it.product, it.quantity)?.let(com.optionslab.app.data.Origins::positionDisplay))
            } + snap.orders.orders.filter { it.pendingQuantity > 0 && it.status.uppercase() !in setOf("COMPLETE", "CANCELLED", "REJECTED") }.map {
                HomeOrder(it.symbol, "${it.action} ${it.pendingQuantity} · ${it.priceType.lowercase()}${if (it.triggerPrice > 0) " · trigger ${PX.format(it.triggerPrice)}" else ""}",
                    "₹" + PX.format(if (it.price > 0) it.price else it.triggerPrice), null,
                    if (it.status.uppercase().contains("TRIGGER")) "TRIGGER PENDING" else "PENDING", RowTarget.PaperOrder(it),
                    orderSource(owners, "paper:${it.orderId}"))
            }
        } ?: emptyList()
        moneyNote = (paper as? Load.Failed)?.why
    }

    Page {
        // ---- the money: capital first and largest --------------------------------------
        item {
            val cap = money.capital
            val usedShare = if (cap != null && cap > 0) ((money.used ?: 0.0) / cap).toFloat().coerceIn(0f, 1f) else 0f
            LedgerCard {
                Text("Capital", style = Type.label.copy(color = p.inkSoft, fontSize = 13.sp))
                com.optionslab.app.ui.components.FitText(cap?.let { inr(it) } ?: "—", style = Type.figureHuge.copy(color = p.ink, fontSize = 38.sp), minSize = 14.sp)
                if (cap != null && cap > 0) {
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp).height(8.dp).background(p.chip, RoundedCornerShape(50))) {
                        if (usedShare > 0f) Spacer(Modifier.weight(usedShare).fillMaxHeight().background(p.ink, RoundedCornerShape(50)))
                        if (usedShare < 1f) Spacer(Modifier.weight(1f - usedShare))
                    }
                }
                MoneyRow(Modifier.padding(top = 12.dp)) {
                    MoneyFigure("Unused", money.unused?.let { inr(it) }, null, Modifier)
                    MoneyFigure("Used", money.used?.let { inr(it) }, null, Modifier)
                    MoneyFigure("P&L today", money.pnlToday?.let { inr(it, true) }, money.pnlToday?.let { if (it >= 0) p.verdigris else p.oxblood }, Modifier)
                }
                if (moneyNote != null) Note(moneyNote, Modifier.padding(top = 8.dp))
                else if (cap != null && cap > 0) Text("${Math.round(100 * usedShare)}% of capital in use", style = Type.bodySmall.copy(color = p.inkSoft), modifier = Modifier.padding(top = 8.dp))
            }
        }

        // ---- strategies: arm the ones you want ------------------------------------------
        item { strategies() }

        // ---- BANKNIFTY ------------------------------------------------------------------
        item {
            val q = quotes["BANKNIFTY"]
            val today = Market.today()
            val past = daily.filter { it.first.isBefore(today) }
            val prevClose = past.lastOrNull()?.second
            val last = q?.last ?: daily.lastOrNull()?.second
            val (values, ref, slots, labels) = when (range) {
                "1D" -> ChartSpec(q?.spark.orEmpty(), prevClose ?: q?.open, 375,
                    listOf(0 to "09:15", 105 to "11:00", 225 to "13:00", 374 to "15:30"))
                else -> {
                    val n = when (range) { "1W" -> 5; "1M" -> 22; else -> 250 }
                    val series = (past.takeLast(n - 1) + listOfNotNull(q?.let { today to it.last } ?: daily.lastOrNull()?.takeIf { !it.first.isBefore(today) }))
                    val fmt = DateTimeFormatter.ofPattern(if (range == "1Y") "MMM yy" else "d MMM", Locale.ENGLISH)
                    val size = series.size.coerceAtLeast(2)
                    ChartSpec(series.map { it.second }, null, size,
                        if (series.size >= 2) listOf(0 to series.first().first.format(fmt), size / 2 to series[size / 2].first.format(fmt), size - 1 to series.last().first.format(fmt)) else emptyList())
                }
            }
            val base = if (range == "1D") ref else values.firstOrNull()
            val change = if (last != null && base != null) last - base else null
            val up = (change ?: 0.0) >= 0
            LedgerCard {
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        // "Full chart ›" moves under the name when both do not fit (at font 2.0 it wrapped into the name).
                        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("BANKNIFTY", style = Type.label.copy(color = p.inkSoft, fontSize = 13.sp), modifier = Modifier.align(Alignment.CenterVertically))
                            Text("Full chart ›", style = Type.label.copy(color = p.ink, fontSize = 12.sp), maxLines = 1, softWrap = false,
                                modifier = Modifier.align(Alignment.CenterVertically).clickable { onGo("chart") })
                        }
                        // One line, shrunk to fit: a price never breaks mid-number.
                        com.optionslab.app.ui.components.FitText(last?.let { PX.format(it) } ?: "—", style = Type.figureLarge.copy(color = p.ink), minSize = 12.sp)
                    }
                    if (change != null && base != null && base != 0.0) Text(
                        "${if (up) "+" else "−"}${PX.format(abs(change))}\n${if (up) "+" else "−"}${String.format(Locale.ENGLISH, "%.2f", abs(100 * change / base))}%",
                        style = Type.figure.copy(color = if (up) p.verdigris else p.oxblood, fontSize = 14.sp), textAlign = TextAlign.End,
                    )
                }
                if (values.size >= 2) PriceChart(values, ref, slots, labels, Modifier.padding(top = 8.dp))
                else Note(when {
                    range == "1D" && !Market.isTradingDay() -> "Market closed today. Pick 1W, 1M or 1Y for recent days."
                    range == "1D" && Market.minuteNow() < Market.OPEN -> "The day's chart starts at 09:15."
                    else -> note ?: "Loading prices…"
                }, Modifier.padding(vertical = 24.dp))
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("1D", "1W", "1M", "1Y").forEach { r -> Token(r, r == range) { range = r } }
                }
            }
        }

        // ---- live orders -----------------------------------------------------------------
        item {
            LedgerCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Live orders", style = Type.title.copy(color = p.ink, fontSize = 16.sp), modifier = Modifier.weight(1f))
                    Text("All in Trade ›", style = Type.bodySmall.copy(color = p.inkSoft), modifier = Modifier.clickable { onGo("trade") }.padding(4.dp))
                }
                if (orders.isEmpty()) Note("No open positions or pending orders.", Modifier.padding(top = 6.dp))
                orders.forEachIndexed { i, o ->
                    if (i > 0) Rule()
                    Row(Modifier.fillMaxWidth().clickable { onRow(o.target) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            // Wrapped, not cut, when a large font leaves too little room beside the amount.
                            Text(o.name, style = Type.body.copy(color = p.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold))
                            Text(o.detail, style = Type.bodySmall.copy(color = p.inkSoft, fontSize = 12.sp))
                            o.source?.let { SourcePill(it) }
                        }
                        Spacer(Modifier.padding(start = 8.dp))
                        Column(horizontalAlignment = Alignment.End) {
                            Text(o.value, style = Type.figure.copy(color = o.valueColor ?: p.ink, fontSize = 14.sp))
                            StatusPill(o.status)
                        }
                    }
                }
            }
        }
    }
}

private data class ChartSpec(val values: List<Double>, val reference: Double?, val slots: Int, val labels: List<Pair<Int, String>>)

@Composable
private fun MoneyFigure(label: String, value: String?, color: Color?, modifier: Modifier) {
    val p = LocalPalette.current
    Column(modifier) {
        Text(label, style = Type.label.copy(color = p.inkSoft, fontSize = 12.sp))
        Spacer(Modifier.height(2.dp))
        Text(value ?: "—", style = Type.figure.copy(color = color ?: p.ink, fontSize = 15.sp, fontWeight = FontWeight.Bold))
    }
}

/**
 * The money figures side by side in equal columns, 8 dp apart, when each fits its column whole; otherwise
 * (a small phone, a large font) one under the other at full width, so no amount is ever cut.
 */
@Composable
private fun MoneyRow(modifier: Modifier, content: @Composable () -> Unit) {
    androidx.compose.ui.layout.Layout(content, modifier.fillMaxWidth()) { ms, c ->
        val gap = 8.dp.roundToPx()
        val n = ms.size.coerceAtLeast(1)
        val natural = ms.map { it.maxIntrinsicWidth(androidx.compose.ui.unit.Constraints.Infinity) }
        val w = if (c.hasBoundedWidth) c.maxWidth else natural.sum() + gap * (n - 1)
        val each = ((w - gap * (n - 1)) / n).coerceAtLeast(0)
        if (natural.all { it <= each }) {
            val ps = ms.map { it.measure(androidx.compose.ui.unit.Constraints(minWidth = each, maxWidth = each)) }
            val h = ps.maxOfOrNull { it.height } ?: 0
            layout(w, h) { ps.forEachIndexed { i, pl -> pl.placeRelative(i * (each + gap), 0) } }
        } else {
            val ps = ms.map { it.measure(androidx.compose.ui.unit.Constraints(maxWidth = w)) }
            val h = ps.sumOf { it.height } + gap * (ps.size - 1).coerceAtLeast(0)
            layout(w, h) { var y = 0; ps.forEach { pl -> pl.placeRelative(0, y); y += pl.height + gap } }
        }
    }
}

@Composable
private fun StatusPill(status: String) {
    val p = LocalPalette.current
    val (bg, fg) = when (status) {
        "OPEN" -> p.verdigris.copy(alpha = 0.14f) to p.verdigris
        "TRIGGER PENDING" -> p.amber.copy(alpha = 0.16f) to p.amber
        else -> p.chip to p.inkSoft
    }
    Text(status, style = Type.label.copy(color = fg, fontSize = 10.sp, fontWeight = FontWeight.Bold),
        modifier = Modifier.padding(top = 3.dp).background(bg, RoundedCornerShape(50)).padding(horizontal = 7.dp, vertical = 2.dp))
}
