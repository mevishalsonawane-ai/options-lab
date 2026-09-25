package com.optionslab.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.optionslab.app.data.Paper
import com.optionslab.app.ui.AppModel
import com.optionslab.app.ui.Load
import com.optionslab.app.ui.components.BrassButton
import com.optionslab.app.ui.components.FullSpinner
import com.optionslab.app.ui.components.LedgerCard
import com.optionslab.app.ui.components.LedgerLine
import com.optionslab.app.ui.components.Note
import com.optionslab.app.ui.components.RollingFigure
import com.optionslab.app.ui.components.Rule
import com.optionslab.app.ui.components.Stamp
import com.optionslab.app.ui.rs
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import com.optionslab.engine.Right
import java.time.LocalDate
import java.util.Locale

private val secure = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn)
private fun px(x: Double) = String.format(Locale.ENGLISH, "%,.2f", x)

/**
 * The sandbox account on the Trade tab: IraAlgo's paper trading, with
 * public Upstox prices and nothing sent anywhere.
 */
fun LazyListScope.paperTrade(model: AppModel, snap: Load<Paper.Snapshot>, book: String, onBook: (String) -> Unit,
                             onReset: () -> Unit) {
    item {
        LedgerCard(accent = LocalPalette.current.verdigris) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("SANDBOX · PAPER ACCOUNT", style = Type.label.copy(color = LocalPalette.current.verdigris), modifier = Modifier.weight(1f))
                Stamp("No broker", LocalPalette.current.verdigris, animate = false)
            }
            Note("IraAlgo's sandbox engine on the phone: margin, fills, MIS square-off at 15:15 and expiry settlement are simulated from Upstox's public prices. Nothing reaches Zerodha.")
        }
    }
    item { PaperOrderForm(model) }
    item {
        ParamTokens("Book", listOf("Positions", "Orders", "Trades", "Funds").map { it to (it.lowercase() == book) }) { i ->
            onBook(listOf("positions", "orders", "trades", "funds")[i])
        }
    }
    when (snap) {
        Load.Idle -> item { LedgerCard { FullSpinner("Opening the paper account") } }
        is Load.Busy -> item { LedgerCard { FullSpinner(snap.label) } }
        is Load.Failed -> item { LedgerCard(accent = LocalPalette.current.amber) { Note(snap.why) } }
        is Load.Done -> {
            val v = snap.value
            if (!v.priced) item { LedgerCard(accent = LocalPalette.current.amber) { Note("No fresh prices from Upstox just now; resting orders wait and positions show their last mark.") } }
            when (book) {
                "orders" -> item { PaperOrders(model, v) }
                "trades" -> item { PaperTrades(v) }
                "funds" -> item { PaperFunds(v, onReset) }
                else -> item { PaperPositions(model, v) }
            }
        }
    }
}

@Composable
private fun PaperOrderForm(model: AppModel) {
    val p = LocalPalette.current
    var underlying by remember { mutableStateOf("NIFTY") }
    var expiries by remember { mutableStateOf<List<LocalDate>>(emptyList()) }
    var expiry by remember { mutableStateOf<LocalDate?>(null) }
    var strike by remember { mutableStateOf("") }
    var right by remember { mutableStateOf(Right.PE) }
    var action by remember { mutableStateOf("SELL") }
    var lots by remember { mutableStateOf(1) }
    var type by remember { mutableStateOf("MARKET") }
    var product by remember { mutableStateOf("NRML") }
    var price by remember { mutableStateOf("") }
    var trigger by remember { mutableStateOf("") }
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(underlying) { expiries = model.paperExpiries(underlying); expiry = expiries.firstOrNull() }
    LedgerCard(title = "Paper order") {
        if (!open) {
            BrassButton("New paper order", Modifier.fillMaxWidth(), tone = p.verdigris) { open = true }
        } else {
            ParamTokens("Underlying", listOf("NIFTY", "BANKNIFTY").map { it to (it == underlying) }) { underlying = listOf("NIFTY", "BANKNIFTY")[it] }
            if (expiries.isEmpty()) Note("Loading the listed expiries from Upstox…")
            ParamTokens("Expiry", expiries.map { it.toString().substring(5) to (it == expiry) }) { expiry = expiries[it] }
            ParamTokens("Option", listOf("PE" to (right == Right.PE), "CE" to (right == Right.CE))) { right = if (it == 0) Right.PE else Right.CE }
            ParamTokens("Side", listOf("SELL" to (action == "SELL"), "BUY" to (action == "BUY"))) { action = if (it == 0) "SELL" else "BUY" }
            val lotChoices = listOf(1, 2, 3, 5, 10)
            ParamTokens("Lots", lotChoices.map { "$it" to (it == lots) }) { lots = lotChoices[it] }
            val types = listOf("MARKET", "LIMIT", "SL", "SL-M")
            ParamTokens("Type", types.map { it to (it == type) }) { type = types[it] }
            ParamTokens("Product", listOf("NRML" to (product == "NRML"), "MIS" to (product == "MIS"))) { product = if (it == 0) "NRML" else "MIS" }
            OutlinedTextField(strike, { strike = it.filter(Char::isDigit) }, label = { Text("Strike") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            if (type == "LIMIT" || type == "SL") OutlinedTextField(price, { price = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Price") },
                singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            if (type == "SL" || type == "SL-M") OutlinedTextField(trigger, { trigger = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Trigger") },
                singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BrassButton("Place paper order", Modifier.weight(1f), tone = p.verdigris, enabled = expiry != null && strike.toDoubleOrNull() != null) {
                    model.paperPlace(underlying, expiry!!, strike.toDouble(), right, action, lots, type, product, price.toDoubleOrNull(), trigger.toDoubleOrNull())
                }
                BrassButton("Close", tone = p.inkFaint) { open = false }
            }
        }
    }
}

@Composable
private fun PaperPositions(model: AppModel, v: Paper.Snapshot) {
    val p = LocalPalette.current
    val book = v.positions
    LedgerCard(title = "Paper positions") {
        Text("TODAY", style = Type.label.copy(color = p.inkSoft))
        RollingFigure(book.totalPnlToday, { rs(it, true) }, Type.figureLarge.copy(color = if (book.totalPnlToday >= 0) p.verdigris else p.oxblood))
        LedgerLine("Unrealised", rs(book.totalUnrealizedPnl, true))
        LedgerLine("Realised today", rs(book.totalTodayRealizedPnl, true))
        if (book.positions.isEmpty()) Note("No paper positions.")
        book.positions.forEach { ps ->
            Rule(Modifier.padding(vertical = 6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(ps.symbol, style = Type.figure.copy(color = p.ink, fontSize = 14.sp))
                    Text("${ps.product} · ${if (ps.quantity > 0) "LONG" else if (ps.quantity < 0) "SHORT" else "CLOSED"} ${kotlin.math.abs(ps.quantity)}" +
                        if (ps.quantity != 0) " · avg ${px(ps.averagePrice)} → ${px(ps.ltp)}" else "", style = Type.figure.copy(color = p.inkSoft, fontSize = 11.sp))
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(rs(ps.totalPnlToday, true), style = Type.figure.copy(color = if (ps.totalPnlToday >= 0) p.verdigris else p.oxblood))
                    if (ps.quantity != 0) TextButton({ model.paperClose(ps.symbol, ps.product) }) { Text("Close", style = Type.label.copy(color = p.oxblood)) }
                }
            }
        }
    }
}

@Composable
private fun PaperOrders(model: AppModel, v: Paper.Snapshot) {
    val p = LocalPalette.current
    var editing by remember { mutableStateOf<com.optionslab.engine.sandbox.OrderRow?>(null) }
    val st = v.orders.statistics
    LedgerCard(title = "Paper order book") {
        LedgerLine("Buy / sell", "${st.totalBuyOrders} / ${st.totalSellOrders}")
        LedgerLine("Complete · open · pending · rejected", "${st.totalCompletedOrders} · ${st.totalOpenOrders} · ${st.totalTriggerPendingOrders} · ${st.totalRejectedOrders}")
        if (v.orders.orders.isEmpty()) Note("No paper orders this session.")
        v.orders.orders.forEach { o ->
            Rule(Modifier.padding(vertical = 5.dp))
            val tone = when (o.status) { "complete" -> p.verdigris; "rejected", "cancelled" -> p.oxblood; else -> p.amber }
            Text("${o.action} ${o.symbol} ×${o.quantity}", style = Type.figure.copy(color = if (o.action == "SELL") p.oxblood else p.verdigris, fontSize = 13.sp))
            Text("${o.product} · ${o.priceType}${if (o.price > 0) " ${px(o.price)}" else ""}${if (o.triggerPrice > 0) " trg ${px(o.triggerPrice)}" else ""} · ${o.timestamp.takeLast(8)}",
                style = Type.figure.copy(color = p.inkSoft, fontSize = 11.sp))
            Text("${o.status.uppercase()}${if (o.filledQuantity > 0) " · ${o.filledQuantity} @ ${px(o.averagePrice)}" else ""}${if (o.rejectionReason.isNotBlank()) " · ${o.rejectionReason}" else ""}",
                style = Type.figure.copy(color = tone, fontSize = 11.sp))
            if (o.status == "open" || o.status == "trigger pending") Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton({ editing = o }) { Text("Modify", style = Type.label.copy(color = p.inkSoft)) }
                TextButton({ model.paperCancel(o.orderId) }) { Text("Cancel", style = Type.label.copy(color = p.oxblood)) }
            }
        }
    }
    editing?.let { o ->
        var qty by remember(o) { mutableStateOf(o.quantity.toString()) }
        var price by remember(o) { mutableStateOf(if (o.price > 0) "%.2f".format(Locale.ENGLISH, o.price) else "") }
        var trig by remember(o) { mutableStateOf(if (o.triggerPrice > 0) "%.2f".format(Locale.ENGLISH, o.triggerPrice) else "") }
        AlertDialog(
            onDismissRequest = { editing = null }, properties = secure,
            title = { Text("Modify paper order", style = Type.title) },
            text = {
                Column {
                    OutlinedTextField(qty, { qty = it.filter(Char::isDigit) }, label = { Text("Quantity (units)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    if (o.priceType == "LIMIT" || o.priceType == "SL") OutlinedTextField(price, { price = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("Price") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    if (o.priceType == "SL" || o.priceType == "SL-M") OutlinedTextField(trig, { trig = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("Trigger") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                }
            },
            confirmButton = {
                TextButton({
                    model.paperModify(o.orderId, qty.toIntOrNull()?.takeIf { it != o.quantity },
                        price.toDoubleOrNull()?.takeIf { o.priceType == "LIMIT" || o.priceType == "SL" },
                        trig.toDoubleOrNull()?.takeIf { o.priceType == "SL" || o.priceType == "SL-M" })
                    editing = null
                }) { Text("Modify") }
            },
            dismissButton = { TextButton({ editing = null }) { Text("Close") } },
        )
    }
}

@Composable
private fun PaperTrades(v: Paper.Snapshot) {
    val p = LocalPalette.current
    LedgerCard(title = "Paper trade book") {
        if (v.trades.isEmpty()) Note("No paper trades this session.")
        v.trades.forEachIndexed { i, t ->
            if (i > 0) Rule(Modifier.padding(vertical = 4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${t.action} ${t.symbol}", style = Type.figure.copy(color = if (t.action == "SELL") p.oxblood else p.verdigris, fontSize = 13.sp))
                    Text("${t.product} · ${t.timestamp.takeLast(8)}", style = Type.figure.copy(color = p.inkSoft, fontSize = 11.sp))
                }
                Text("${t.quantity} @ ${px(t.price)}", style = Type.figure.copy(color = p.ink, fontSize = 13.sp))
            }
        }
    }
}

@Composable
private fun PaperFunds(v: Paper.Snapshot, onReset: () -> Unit) {
    val p = LocalPalette.current
    val f = v.funds
    LedgerCard(title = "Paper funds") {
        LedgerLine("Available", rs(f.availableCash), p.verdigris)
        LedgerLine("Used margin", rs(f.utilisedDebits))
        LedgerLine("Unrealised M2M", rs(f.m2mUnrealized, true))
        LedgerLine("Realised today", rs(f.todayRealizedPnl, true))
        LedgerLine("Realised, all time", rs(f.totalRealizedPnl, true))
        LedgerLine("Total P&L", rs(f.totalPnl, true), if (f.totalPnl >= 0) p.verdigris else p.oxblood)
        LedgerLine("Resets", "${f.resetCount} · last ${f.lastReset.take(10)}")
        Spacer(Modifier.height(8.dp))
        BrassButton("Reset the paper account", Modifier.fillMaxWidth(), tone = p.oxblood, onClick = onReset)
    }
}

/** Starting capital for a fresh paper account. */
@Composable
fun PaperResetDialog(model: AppModel, onClose: () -> Unit) {
    val choices = listOf(500_000.0, 1_000_000.0, 5_000_000.0, 10_000_000.0)
    var pick by remember { mutableStateOf(10_000_000.0) }
    AlertDialog(
        onDismissRequest = onClose, properties = secure,
        title = { Text("Reset the paper account?", style = Type.title) },
        text = {
            Column {
                Text("Every paper order, trade and position is cleared and funds start again at:", style = Type.bodySmall)
                ParamTokens("Starting capital", choices.map { rs(it) to (it == pick) }) { pick = choices[it] }
            }
        },
        confirmButton = { TextButton({ model.paperReset(pick); onClose() }) { Text("Reset") } },
        dismissButton = { TextButton(onClose) { Text("Keep") } },
    )
}
