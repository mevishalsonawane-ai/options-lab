package com.optionslab.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.optionslab.app.data.Broker
import com.optionslab.app.data.Market
import com.optionslab.app.data.PnlTracker
import com.optionslab.app.ui.Account
import com.optionslab.app.ui.AppModel
import com.optionslab.app.ui.Load
import com.optionslab.app.ui.Page
import com.optionslab.app.ui.components.BrassButton
import com.optionslab.app.ui.components.FullSpinner
import com.optionslab.app.ui.components.InkCurve
import com.optionslab.app.ui.components.LedgerCard
import com.optionslab.app.ui.components.LedgerLine
import com.optionslab.app.ui.components.Note
import com.optionslab.app.ui.components.RollingFigure
import com.optionslab.app.ui.components.Rule
import com.optionslab.app.ui.rs
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import kotlinx.coroutines.delay
import java.util.Locale

private val secure = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn)

private fun px(x: Double) = String.format(Locale.ENGLISH, "%,.2f", x)

/**
 * Your Zerodha account, live: positions with square-off, the order book with
 * modify and cancel, the day's trades, delivery holdings, funds and the day's
 * P&L curve - the PC's positions/orderbook/tradebook/holdings/P&L pages in
 * one place. Every action that sends something opens the same review as a
 * ticket: price check, hold-to-send, and your PIN or fingerprint.
 */
@Composable
fun TradeScreen(model: AppModel) {
    val p = LocalPalette.current
    val s by model.settings.collectAsState()
    val b by model.broker.collectAsState()
    val acct by model.account.collectAsState()
    val pnl by model.pnlSeries.collectAsState()
    val owners by model.orderOwners.collectAsState()
    var book by rememberSaveable { mutableStateOf("positions") }
    var modifying by remember { mutableStateOf<Broker.OrderRow?>(null) }
    var cancelling by remember { mutableStateOf<Broker.OrderRow?>(null) }
    var cancelAuth by remember { mutableStateOf<Broker.OrderRow?>(null) }
    var protecting by remember { mutableStateOf<GttTarget?>(null) }
    var gttDelete by remember { mutableStateOf<Broker.GttRow?>(null) }
    var gttDeleteAuth by remember { mutableStateOf<Broker.GttRow?>(null) }
    val gtts by model.gtts.collectAsState()
    var selling by remember { mutableStateOf<Broker.Holding?>(null) }
    val paperSnap by model.paper.collectAsState()
    var paperBook by rememberSaveable { mutableStateOf("positions") }
    var resetting by remember { mutableStateOf(false) }

    // Sandbox: the paper engine runs its jobs against fresh public prices.
    com.optionslab.app.ui.PollWhileStarted(s.live) {
        if (s.live) return@PollWhileStarted
        while (true) {
            model.loadPaper(quiet = true)
            delay(if (Market.isOpen()) 30_000 else 300_000)
        }
    }

    // Fresh while the page is open: every 15 s in market hours, 2 min outside.
    com.optionslab.app.ui.PollWhileStarted(b.loggedIn, s.live) {
        if (!b.loggedIn || !s.live) return@PollWhileStarted
        while (true) {
            model.loadAccount(quiet = true)
            delay(if (Market.isOpen()) 15_000 else 120_000)
        }
    }

    Page {
        item { PageTitle("Trade", if (s.live) "Your Zerodha account, live" else "The paper account · sandbox") }
        if (!s.live) {
            paperTrade(model, paperSnap, paperBook, { paperBook = it }, onReset = { resetting = true })
            return@Page
        }
        if (!b.configured || !b.loggedIn) {
            item {
                LedgerCard(accent = p.amber) {
                    Note(if (!b.configured) "Set up your Kite API key and secret first (More → Zerodha)." else "Log in to Zerodha for today to see your account.")
                    if (b.configured) BrassButton("Log in to Zerodha", Modifier.fillMaxWidth().padding(top = 8.dp)) { model.startKiteLogin() }
                }
            }
            return@Page
        }
        item {
            val a = (acct as? Load.Done<Account>)?.value
            ParamTokens("Book", listOf(
                "Positions${a?.let { " ${it.positions.count { x -> x.open }}" } ?: ""}" to (book == "positions"),
                "Orders${a?.let { " ${it.orders.count { x -> x.working }}" } ?: ""}" to (book == "orders"),
                "Trades" to (book == "trades"),
                "Holdings" to (book == "holdings"),
                "Funds · P&L" to (book == "funds"),
            )) { i -> book = listOf("positions", "orders", "trades", "holdings", "funds")[i] }
        }
        when (val a = acct) {
            Load.Idle -> item { LedgerCard { FullSpinner("Reading your Zerodha account") } }
            is Load.Busy -> item { LedgerCard { FullSpinner(a.label) } }
            is Load.Failed -> item { LedgerCard(accent = p.amber) { Note(a.why); BrassButton("Try again", Modifier.fillMaxWidth()) { model.loadAccount() } } }
            is Load.Done -> {
                val v = a.value
                when (book) {
                    "positions" -> item { PositionsCard(model, v) { protecting = it } }
                    "orders" -> {
                        item { OrdersCard(v, owners, onTap = { model.rowAction.value = RowTarget.LiveOrder(it) }, onModify = { modifying = it }, onCancel = { cancelling = it }) }
                        item { GttCard(gtts) { gttDelete = it } }
                    }
                    "trades" -> item { TradesCard(v, owners) { model.rowAction.value = RowTarget.LiveTrade(it) } }
                    "holdings" -> item { HoldingsCard(v, onProtect = { h -> protecting = GttTarget(h.exchange, h.symbol, "CNC", h.qty) }) { selling = it } }
                    else -> {
                        item { PnlCard(v, pnl) }
                        item { FundsCard(v) }
                    }
                }
                item {
                    Text("As of ${v.at.toLocalTime().withNano(0)} IST · refreshes every ${if (Market.isOpen()) "15 s" else "2 min"}",
                        style = Type.italic.copy(color = p.inkFaint, fontSize = 12.sp))
                }
            }
        }
    }

    if (resetting) PaperResetDialog(model) { resetting = false }
    protecting?.let { t -> GttDialog(model, t) { protecting = null } }
    gttDelete?.let { g ->
        AlertDialog(
            onDismissRequest = { gttDelete = null }, properties = secure,
            title = { Text("Delete GTT #${g.id}?", style = Type.title) },
            text = { Text("${g.symbol}: triggers ${g.triggers.joinToString(" / ") { px(it) }}. Deleting it removes this protection.", style = Type.bodySmall) },
            confirmButton = { TextButton({ gttDeleteAuth = g; gttDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton({ gttDelete = null }) { Text("Keep") } },
        )
    }
    gttDeleteAuth?.let { g -> Reauth(model, onOk = { gttDeleteAuth = null; model.deleteGtt(g.id) }, onCancel = { gttDeleteAuth = null }) }
    cancelAuth?.let { o -> Reauth(model, onOk = { cancelAuth = null; model.cancelOrder(o.id, o.variety) }, onCancel = { cancelAuth = null }) }
    modifying?.let { o -> ModifyDialog(model, o) { modifying = null } }
    cancelling?.let { o ->
        AlertDialog(
            onDismissRequest = { cancelling = null }, properties = secure,
            title = { Text("Cancel this order?", style = Type.title) },
            text = { Text("${o.side} ${o.symbol} ×${o.qty} (${o.type}${if (o.price > 0) " @ ${px(o.price)}" else ""}). Filled so far: ${o.filled}.", style = Type.bodySmall) },
            // Cancelling a working stop-loss removes protection, so it is proved like a send.
            confirmButton = { TextButton({ cancelAuth = o; cancelling = null }) { Text("Cancel order") } },
            dismissButton = { TextButton({ cancelling = null }) { Text("Keep") } },
        )
    }
    selling?.let { h ->
        var qty by remember(h) { mutableStateOf(h.qty.toString()) }
        AlertDialog(
            onDismissRequest = { selling = null }, properties = secure,
            title = { Text("Sell ${h.symbol}", style = Type.title) },
            text = {
                Column {
                    Text("You hold ${h.qty}${if (h.t1 > 0) " (+${h.t1} T1, not yet sellable)" else ""}. The sale opens for review first.", style = Type.bodySmall)
                    OutlinedTextField(qty, { qty = it.filter(Char::isDigit).take(7) }, label = { Text("Quantity") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
            },
            confirmButton = {
                TextButton({
                    val n = qty.toIntOrNull() ?: 0
                    if (n in 1..h.qty) { model.planSellHolding(h, n); selling = null } else model.say("Between 1 and ${h.qty}.")
                }) { Text("Review sale") }
            },
            dismissButton = { TextButton({ selling = null }) { Text("Close") } },
        )
    }
}

@Composable
private fun PositionsCard(model: AppModel, a: Account, onProtect: (GttTarget) -> Unit) {
    val p = LocalPalette.current
    val open = a.positions.filter { it.open }
    val closed = a.positions.filter { !it.open }
    LedgerCard(title = "Positions") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("TOTAL P&L", style = Type.label.copy(color = p.inkSoft))
                RollingFigure(a.book.pnl, { rs(it, true) }, Type.figureLarge.copy(color = if (a.book.pnl >= 0) p.verdigris else p.oxblood), calm = true)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("realised ${rs(a.book.realised, true)}", style = Type.figure.copy(color = p.inkSoft, fontSize = 12.sp))
                Text("unrealised ${rs(a.book.unrealised, true)}", style = Type.figure.copy(color = p.inkSoft, fontSize = 12.sp))
                Text("M2M ${rs(a.book.m2m, true)}", style = Type.figure.copy(color = p.inkSoft, fontSize = 12.sp))
            }
        }
        if (open.isEmpty()) Note("Nothing open.")
        open.forEach { ps ->
            Rule(Modifier.padding(vertical = 6.dp))
            PositionRow(ps) { model.rowAction.value = RowTarget.LivePosition(ps) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                BrassButton("Square off", tone = p.oxblood) { model.planSquareOff(ps) }
                BrassButton("Protect (GTT)", tone = p.inkSoft) { onProtect(GttTarget(ps.exchange, ps.symbol, ps.product, ps.qty)) }
            }
        }
        if (open.size > 1) BrassButton("Square off all ${open.size}", Modifier.fillMaxWidth().padding(top = 10.dp), tone = p.oxblood) { model.planSquareOffAll() }
        if (closed.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("CLOSED TODAY", style = Type.label.copy(color = p.inkSoft))
            closed.forEach { ps -> LedgerLine("${ps.symbol} ${ps.product}", rs(ps.pnl, true), if (ps.pnl >= 0) p.verdigris else p.oxblood,
                Modifier.clickable { model.rowAction.value = RowTarget.LivePosition(ps) }) }
        }
        if (a.book.day.isNotEmpty()) Note("Day book: bought ${a.book.day.sumOf { it.buyQty }}, sold ${a.book.day.sumOf { it.sellQty }} across ${a.book.day.size} instruments today.")
    }
}

@Composable
private fun PositionRow(ps: Broker.Position, onTap: () -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.clickable(onClick = onTap), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("${ps.symbol}", style = Type.figure.copy(color = p.ink, fontSize = 14.sp))
            Text("${ps.exchange} · ${ps.product} · ${if (ps.qty > 0) "LONG" else "SHORT"} ${kotlin.math.abs(ps.qty)}" +
                if (ps.overnight != 0) " (overnight ${ps.overnight})" else "", style = Type.figure.copy(color = p.inkSoft, fontSize = 11.sp))
            Text("avg ${px(ps.avg)} → ltp ${px(ps.last)}", style = Type.figure.copy(color = p.inkSoft, fontSize = 11.sp))
        }
        Text(rs(ps.pnl, true), style = Type.figure.copy(color = if (ps.pnl >= 0) p.verdigris else p.oxblood, fontSize = 15.sp))
    }
}

@Composable
private fun OrdersCard(a: Account, owners: Map<String, String>, onTap: (Broker.OrderRow) -> Unit, onModify: (Broker.OrderRow) -> Unit, onCancel: (Broker.OrderRow) -> Unit) {
    val p = LocalPalette.current
    val working = a.orders.filter { it.working }
    val done = a.orders.filter { !it.working }
    LedgerCard(title = "Order book") {
        if (a.orders.isEmpty()) Note("No orders today.")
        if (working.isNotEmpty()) Text("WORKING", style = Type.label.copy(color = p.amber))
        working.forEach { o ->
            OrderLine(o, owners) { onTap(o) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp, bottom = 6.dp)) {
                BrassButton("Modify", tone = p.inkSoft) { onModify(o) }
                BrassButton("Cancel", tone = p.oxblood) { onCancel(o) }
            }
        }
        if (done.isNotEmpty()) {
            if (working.isNotEmpty()) Rule(Modifier.padding(vertical = 6.dp))
            Text("FINISHED", style = Type.label.copy(color = p.inkSoft))
            done.forEach { o -> OrderLine(o, owners) { onTap(o) } }
        }
    }
}

@Composable
private fun OrderLine(o: Broker.OrderRow, owners: Map<String, String>, onTap: () -> Unit) {
    val p = LocalPalette.current
    val tone = when (o.status) { "COMPLETE" -> p.verdigris; "REJECTED", "CANCELLED" -> p.oxblood; else -> p.amber }
    Column(Modifier.fillMaxWidth().clickable(onClick = onTap).padding(vertical = 3.dp)) {
        Text("${o.side} ${o.symbol} ×${o.qty}", style = Type.figure.copy(color = if (o.side == "SELL") p.oxblood else p.verdigris, fontSize = 13.sp))
        Text("${o.exchange} · ${o.product} · ${o.type}${if (o.price > 0) " ${px(o.price)}" else ""}${if (o.trigger > 0) " trg ${px(o.trigger)}" else ""}" +
            " · ${o.placedAt.takeLast(8)}", style = Type.figure.copy(color = p.inkSoft, fontSize = 11.sp))
        Text("${o.status} · filled ${o.filled}${if (o.filled > 0) " @ ${px(o.avg)}" else ""}${if (o.message.isNotBlank()) " · ${o.message}" else ""}",
            style = Type.figure.copy(color = tone, fontSize = 11.sp))
        OrderSourcePill(owners, "kite:${o.id}", o.tag)
    }
}

@Composable
private fun TradesCard(a: Account, owners: Map<String, String>, onTap: (Broker.Trade) -> Unit) {
    val p = LocalPalette.current
    LedgerCard(title = "Trade book") {
        if (a.trades.isEmpty()) Note("No trades today.")
        a.trades.forEachIndexed { i, t ->
            if (i > 0) Rule(Modifier.padding(vertical = 4.dp))
            Row(Modifier.clickable { onTap(t) }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${t.side} ${t.symbol}", style = Type.figure.copy(color = if (t.side == "SELL") p.oxblood else p.verdigris, fontSize = 13.sp))
                    Text("${t.exchange} · ${t.product} · ${t.at.takeLast(8)} · order …${t.orderId.takeLast(6)}", style = Type.figure.copy(color = p.inkSoft, fontSize = 11.sp))
                    OrderSourcePill(owners, "kite:${t.orderId}", a.orders.firstOrNull { it.id == t.orderId }?.tag.orEmpty())
                }
                Text("${t.qty} @ ${px(t.price)}", style = Type.figure.copy(color = p.ink, fontSize = 13.sp))
            }
        }
        if (a.trades.isNotEmpty()) {
            val bought = a.trades.filter { it.side == "BUY" }.sumOf { it.qty * it.price }
            val sold = a.trades.filter { it.side == "SELL" }.sumOf { it.qty * it.price }
            Spacer(Modifier.height(8.dp))
            LedgerLine("Bought", rs(bought)); LedgerLine("Sold", rs(sold))
        }
    }
}

@Composable
private fun HoldingsCard(a: Account, onProtect: (Broker.Holding) -> Unit, onSell: (Broker.Holding) -> Unit) {
    val p = LocalPalette.current
    LedgerCard(title = "Holdings") {
        if (a.holdings.isEmpty()) Note("No delivery holdings.") else HoldingsBody(a, onProtect, onSell)
    }
}

@Composable
private fun HoldingsBody(a: Account, onProtect: (Broker.Holding) -> Unit, onSell: (Broker.Holding) -> Unit) {
    val p = LocalPalette.current
    run {
        val invested = a.holdings.sumOf { it.invested }
        val value = a.holdings.sumOf { it.value }
        val day = a.holdings.sumOf { it.dayChange * (it.qty + it.t1) }
        LedgerLine("Invested", rs(invested))
        LedgerLine("Current value", rs(value))
        LedgerLine("Total P&L", "${rs(value - invested, true)} (${String.format(Locale.ENGLISH, "%+.2f%%", if (invested > 0) 100 * (value - invested) / invested else 0.0)})",
            if (value >= invested) p.verdigris else p.oxblood)
        LedgerLine("Today", rs(day, true), if (day >= 0) p.verdigris else p.oxblood)
        a.holdings.forEach { h ->
            Rule(Modifier.padding(vertical = 6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(h.symbol, style = Type.figure.copy(color = p.ink, fontSize = 14.sp))
                    Text("${h.qty + h.t1} @ ${px(h.avg)} → ${px(h.last)} · day ${String.format(Locale.ENGLISH, "%+.2f%%", h.dayChangePct)}",
                        style = Type.figure.copy(color = p.inkSoft, fontSize = 11.sp))
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(rs(h.pnl, true), style = Type.figure.copy(color = if (h.pnl >= 0) p.verdigris else p.oxblood))
                    if (h.qty > 0) Row {
                        TextButton({ onProtect(h) }) { Text("Protect", style = Type.label.copy(color = p.inkSoft)) }
                        TextButton({ onSell(h) }) { Text("Sell", style = Type.label.copy(color = p.oxblood)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PnlCard(a: Account, series: List<PnlTracker.Point>) {
    val p = LocalPalette.current
    LedgerCard(title = "Today's P&L") {
        RollingFigure(a.book.pnl, { rs(it, true) }, Type.figureLarge.copy(color = if (a.book.pnl >= 0) p.verdigris else p.oxblood), calm = true)
        if (series.size >= 2) {
            InkCurve(series.map { it.pnl }, emptyList(), null, calm = true)
            val hi = series.maxBy { it.pnl }; val lo = series.minBy { it.pnl }
            fun hm(m: Int) = "%02d:%02d".format(m / 60, m % 60)
            LedgerLine("High", "${rs(hi.pnl, true)} at ${hm(hi.minute)}", p.verdigris)
            LedgerLine("Low", "${rs(lo.pnl, true)} at ${hm(lo.minute)}", p.oxblood)
            LedgerLine("From ${hm(series.first().minute)}", "${series.size} samples")
        } else Note("The curve builds while the app is open: one point a minute from Zerodha's positions. It is kept, encrypted, until the day ends.")
    }
}

@Composable
private fun FundsCard(a: Account) {
    val p = LocalPalette.current
    LedgerCard(title = "Funds · equity segment") {
        val f = a.funds
        if (f == null) Note("Margins unavailable just now.") else FundsBody(f)
    }
}

@Composable
private fun FundsBody(f: Broker.Funds) {
    val p = LocalPalette.current
    run {
        LedgerLine("Available", rs(f.available), p.verdigris)
        LedgerLine("Used margin", rs(f.used))
        LedgerLine("Net", rs(f.net))
        Rule(Modifier.padding(vertical = 6.dp))
        LedgerLine("Opening balance", rs(f.openingBalance))
        LedgerLine("Collateral", rs(f.collateral))
        LedgerLine("SPAN", rs(f.span))
        LedgerLine("Exposure", rs(f.exposure))
        LedgerLine("Option premium", rs(f.optionPremium))
        LedgerLine("Realised M2M", rs(f.realised, true))
        LedgerLine("Unrealised M2M", rs(f.m2m, true))
    }
}

/** Change a working order: quantity, type, price, trigger. Re-proves who you are first. */
@Composable
internal fun ModifyDialog(model: AppModel, o: Broker.OrderRow, onClose: () -> Unit) {
    val p = LocalPalette.current
    var qty by remember { mutableStateOf(o.qty.toString()) }
    var type by remember { mutableStateOf(o.type) }
    var price by remember { mutableStateOf(if (o.price > 0) "%.2f".format(Locale.ENGLISH, o.price) else "") }
    var trigger by remember { mutableStateOf(if (o.trigger > 0) "%.2f".format(Locale.ENGLISH, o.trigger) else "") }
    var confirming by remember { mutableStateOf(false) }
    val types = listOf("LIMIT", "SL", "SL-M", "MARKET")
    AlertDialog(
        onDismissRequest = onClose, properties = secure,
        title = { Text("Modify ${o.symbol}", style = Type.title) },
        text = {
            Column {
                Text("${o.side} · ${o.product} · filled ${o.filled} of ${o.qty}", style = Type.bodySmall.copy(color = p.inkSoft))
                ParamTokens("Type", types.map { it to (it == type) }) { type = types[it] }
                OutlinedTextField(qty, { qty = it.filter(Char::isDigit).take(7) }, label = { Text("Quantity") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                if (type == "LIMIT" || type == "SL") OutlinedTextField(price, { price = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Price") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                if (type == "SL" || type == "SL-M") OutlinedTextField(trigger, { trigger = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Trigger") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
        },
        confirmButton = { TextButton({ confirming = true }) { Text("Confirm change") } },
        dismissButton = { TextButton(onClose) { Text("Close") } },
    )
    if (confirming) Reauth(model, onOk = {
        confirming = false
        model.modifyOrder(o, qty.toIntOrNull() ?: 0, type, price.toDoubleOrNull(), trigger.toDoubleOrNull())
        onClose()
    }, onCancel = { confirming = false })
}

/** What a GTT protects: one position or holding. */
data class GttTarget(val exchange: String, val symbol: String, val product: String, val netQty: Int)

@Composable
private fun GttCard(list: List<Broker.GttRow>, onDelete: (Broker.GttRow) -> Unit) {
    val p = LocalPalette.current
    LedgerCard(title = "GTT (held at Zerodha)") {
        if (list.isEmpty()) Note("No GTTs. \"Protect (GTT)\" on a position leaves a stop-loss and/or target at Zerodha that works even when this phone is off.")
        list.forEach { g ->
            Rule(Modifier.padding(vertical = 4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${g.symbol} · ${g.type} · ${g.status}", style = Type.figure.copy(color = if (g.status == "active") p.ink else p.inkSoft, fontSize = 12.sp))
                    Text("triggers ${g.triggers.joinToString(" / ") { px(it) }} · ${g.orders}", style = Type.figure.copy(color = p.inkSoft, fontSize = 10.sp))
                }
                if (g.status == "active") TextButton({ onDelete(g) }) { Text("Delete", style = Type.label.copy(color = p.oxblood)) }
            }
        }
    }
}

/** Stop and/or target for one position, checked, then placed after PIN or fingerprint. */
@Composable
private fun GttDialog(model: AppModel, t: GttTarget, onClose: () -> Unit) {
    val p = LocalPalette.current
    val plan by model.gttPlan.collectAsState()
    var stop by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }
    var auth by remember { mutableStateOf(false) }
    val long = t.netQty > 0
    // A plan checked for another position (or before a failed placement) never carries over.
    LaunchedEffect(t) { model.dismissGtt() }
    AlertDialog(
        onDismissRequest = { model.dismissGtt(); onClose() }, properties = secure,
        title = { Text("Protect ${t.symbol}", style = Type.title) },
        text = {
            Column {
                Text("${if (long) "Long" else "Short"} ${kotlin.math.abs(t.netQty)} · ${t.product}. A GTT lives at Zerodha: it fires even if this phone is off. " +
                    "With both a stop and a target it is one-cancels-other.", style = Type.bodySmall)
                OutlinedTextField(stop, { stop = it.filter { c -> c.isDigit() || c == '.' }; model.dismissGtt() }, singleLine = true,
                    label = { Text("Stop-loss trigger (${if (long) "below" else "above"} the price)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(target, { target = it.filter { c -> c.isDigit() || c == '.' }; model.dismissGtt() }, singleLine = true,
                    label = { Text("Target trigger (optional)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                TextButton({ model.planGtt(t.exchange, t.symbol, t.product, t.netQty, stop.toDoubleOrNull(), target.toDoubleOrNull()) }) {
                    Text("Check", style = Type.label.copy(color = p.brass))
                }
                when (val l = plan) {
                    is Load.Busy -> Text(l.label, style = Type.italic)
                    is Load.Failed -> Text(l.why, style = Type.bodySmall.copy(color = p.oxblood))
                    is Load.Done -> {
                        l.value.why.forEach { Text("✕ $it", style = Type.bodySmall.copy(color = p.oxblood)) }
                        l.value.gtt?.let { g ->
                            Text("Last price ${px(g.lastPrice)} · ${g.type}", style = Type.figure.copy(fontSize = 12.sp))
                            g.triggers.zip(g.orders).forEach { (tr, o) ->
                                Text("at ${px(tr)} → ${o.side} ${o.quantity} LIMIT ${px(o.price ?: 0.0)}", style = Type.figure.copy(fontSize = 12.sp))
                            }
                        }
                    }
                    Load.Idle -> Unit
                }
            }
        },
        confirmButton = {
            val ready = (plan as? Load.Done<AppModel.GttPlan>)?.value?.gtt != null
            TextButton({ auth = true }, enabled = ready) { Text("Place GTT") }
        },
        dismissButton = { TextButton({ model.dismissGtt(); onClose() }) { Text("Close") } },
    )
    if (auth) Reauth(model, onOk = { auth = false; model.placeGtt(); model.dismissGtt(); onClose() }, onCancel = { auth = false })
}
