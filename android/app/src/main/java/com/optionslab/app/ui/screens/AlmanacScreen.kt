package com.optionslab.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
private data class HomeOrder(val name: String, val detail: String, val value: String, val valueColor: Color?, val status: String)

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
    val p = LocalPalette.current
    val s by model.settings.collectAsState()
    val quotes by model.quotes.collectAsState()
    val note by model.quoteNote.collectAsState()
    val daily by model.bankNiftyDaily.collectAsState()
    val account by model.account.collectAsState()
    val paper by model.paper.collectAsState()
    var range by rememberSaveable { mutableStateOf("1D") }

    // Prices poll only while Home is on screen and the app is in front.
    com.optionslab.app.ui.PollWhileStarted {
        model.startQuotes()
        try { kotlinx.coroutines.awaitCancellation() } finally { model.stopQuotes() }
    }
    // The money and the orders refresh every 20 s while Home is open.
    com.optionslab.app.ui.PollWhileStarted(s.live) {
        while (true) {
            if (s.live) { if (com.optionslab.app.data.Broker.loggedIn) model.loadAccount(quiet = true) } else model.loadPaper(quiet = true)
            delay(20_000)
        }
    }
    LaunchedEffect(s.live) { model.loadBankNiftyDaily() }

    val money: Money
    val orders: List<HomeOrder>
    val moneyNote: String?
    if (s.live) {
        val a = (account as? Load.Done)?.value
        money = Money(a?.book?.m2m, a?.funds?.available, a?.funds?.used)
        orders = a?.let { acc ->
            acc.positions.filter { it.qty != 0 }.map {
                HomeOrder(it.symbol, "${if (it.qty < 0) "SELL" else "BUY"} ${abs(it.qty)} · avg ${PX.format(it.avg)} · LTP ${PX.format(it.last)}",
                    inr(it.pnl, true), if (it.pnl >= 0) p.verdigris else p.oxblood, "OPEN")
            } + acc.orders.filter { it.working }.map {
                HomeOrder(it.symbol, "${it.side} ${it.pending.takeIf { n -> n > 0 } ?: it.qty} · ${it.type.lowercase()}${if (it.trigger > 0) " · trigger ${PX.format(it.trigger)}" else ""}",
                    "₹" + PX.format(if (it.price > 0) it.price else it.trigger), null, if (it.status == "TRIGGER PENDING") "TRIGGER PENDING" else "PENDING")
            }
        } ?: emptyList()
        moneyNote = when {
            !com.optionslab.app.data.Broker.loggedIn -> "Log in to Zerodha for today to see your money and orders."
            account is Load.Failed -> (account as Load.Failed).why
            else -> null
        }
    } else {
        val v = (paper as? Load.Done)?.value
        money = Money(v?.funds?.let { it.todayRealizedPnl + it.m2mUnrealized }, v?.funds?.availableCash, v?.funds?.utilisedDebits)
        orders = v?.let { snap ->
            snap.positions.positions.filter { it.quantity != 0 }.map {
                HomeOrder(it.symbol, "${if (it.quantity < 0) "SELL" else "BUY"} ${abs(it.quantity)} · avg ${PX.format(it.averagePrice)} · LTP ${PX.format(it.ltp)}",
                    inr(it.pnl, true), if (it.pnl >= 0) p.verdigris else p.oxblood, "OPEN")
            } + snap.orders.orders.filter { it.pendingQuantity > 0 && it.status.uppercase() !in setOf("COMPLETE", "CANCELLED", "REJECTED") }.map {
                HomeOrder(it.symbol, "${it.action} ${it.pendingQuantity} · ${it.priceType.lowercase()}${if (it.triggerPrice > 0) " · trigger ${PX.format(it.triggerPrice)}" else ""}",
                    "₹" + PX.format(if (it.price > 0) it.price else it.triggerPrice), null,
                    if (it.status.uppercase().contains("TRIGGER")) "TRIGGER PENDING" else "PENDING")
            }
        } ?: emptyList()
        moneyNote = (paper as? Load.Failed)?.why
    }

    Page {
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
                        Text("BANKNIFTY", style = Type.label.copy(color = p.inkSoft, fontSize = 13.sp))
                        Text(last?.let { PX.format(it) } ?: "—", style = Type.figureLarge.copy(color = p.ink))
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

        // ---- the money --------------------------------------------------------------------
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MoneyTile("P&L today", money.pnlToday?.let { inr(it, true) }, money.pnlToday?.let { if (it >= 0) p.verdigris else p.oxblood }, Modifier.weight(1f))
                MoneyTile("Unused", money.unused?.let { inr(it) }, null, Modifier.weight(1f))
                MoneyTile("Used", money.used?.let { inr(it) }, null, Modifier.weight(1f))
            }
            val cap = money.capital
            Text(
                moneyNote ?: if (cap != null && cap > 0) "Capital ${inr(cap)} · ${Math.round(100 * (money.used ?: 0.0) / cap)}% in use" else " ",
                style = Type.bodySmall.copy(color = p.inkSoft), modifier = Modifier.padding(start = 2.dp, top = 8.dp),
            )
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
                    Row(Modifier.fillMaxWidth().clickable { onGo("trade") }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(o.name, style = Type.body.copy(color = p.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold), maxLines = 1)
                            Text(o.detail, style = Type.bodySmall.copy(color = p.inkSoft, fontSize = 12.sp), maxLines = 1)
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
private fun MoneyTile(label: String, value: String?, color: Color?, modifier: Modifier) {
    val p = LocalPalette.current
    LedgerCard(modifier) {
        Text(label, style = Type.label.copy(color = p.inkSoft, fontSize = 12.sp), maxLines = 1)
        Spacer(Modifier.height(2.dp))
        Text(value ?: "—", style = Type.figure.copy(color = color ?: p.ink, fontSize = 15.sp, fontWeight = FontWeight.Bold), maxLines = 1)
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
