package com.optionslab.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import com.optionslab.app.ui.components.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import com.optionslab.app.ui.components.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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

private val secure get() = DialogProperties(securePolicy = com.optionslab.app.security.Capture.policy)
private fun px(x: Double) = String.format(Locale.ENGLISH, "%,.2f", x)

/**
 * The sandbox account on the Trade tab: IraAlgo's paper trading, with
 * public Upstox prices and nothing sent anywhere.
 */
fun LazyListScope.paperTrade(model: AppModel, snap: Load<Paper.Snapshot>, book: String, onBook: (String) -> Unit,
                             onReset: () -> Unit) {
    // Stable keys and a fixed number of rows: a reset or reload changes what the rows hold, never how many there
    // are, so the order form keeps its slot (and what was being typed), and the list is never measured against a
    // row count that just shrank (CI: "Index 5, size 5" inside the lazy list's measure after a reload).
    item(key = "paper.head") {
        Column {
            LedgerCard(accent = LocalPalette.current.verdigris) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("SANDBOX · PAPER ACCOUNT", style = Type.label.copy(color = LocalPalette.current.verdigris), modifier = Modifier.weight(1f))
                    Stamp("No broker", LocalPalette.current.verdigris, animate = false)
                }
                Note("IraAlgo's sandbox engine on the phone: margin, fills, MIS square-off at 15:15 and expiry settlement are simulated from Upstox's public prices. Nothing reaches Zerodha.")
            }
            if (snap is Load.Done) { Spacer(Modifier.height(14.dp)); PaperBalance(snap.value, onReset) }
        }
    }
    item(key = "paper.form") { PaperOrderForm(model) }
    item(key = "paper.book") {
        ParamTokens("Book", listOf("Positions", "Orders", "Trades", "Funds").map { it to (it.lowercase() == book) }) { i ->
            onBook(listOf("positions", "orders", "trades", "funds")[i])
        }
    }
    item(key = "paper.body") {
        when (snap) {
            Load.Idle -> LedgerCard { FullSpinner("Opening the paper account") }
            is Load.Busy -> LedgerCard { FullSpinner(snap.label) }
            is Load.Failed -> com.optionslab.app.ui.components.AlertOn(snap.why)
            is Load.Done -> Column {
                val v = snap.value
                if (!v.priced) {
                    LedgerCard(accent = LocalPalette.current.amber) { Note("No fresh prices from Upstox just now; resting orders wait and positions show their last mark.") }
                    Spacer(Modifier.height(14.dp))
                }
                when (book) {
                    "orders" -> PaperOrders(model, v)
                    "trades" -> PaperTrades(model, v)
                    "funds" -> PaperFunds(model, v, onReset)
                    else -> PaperPositions(model, v)
                }
            }
        }
    }
}

@Composable
private fun PaperOrderForm(model: AppModel) {
    val p = LocalPalette.current
    // What is typed is saveable (plain strings and numbers), so it outlives the row scrolling
    // out of the list; the listed expiries and strikes are simply fetched again.
    var underlying by rememberSaveable { mutableStateOf("NIFTY") }
    var expiries by remember { mutableStateOf<List<LocalDate>>(emptyList()) }
    var expiryIso by rememberSaveable { mutableStateOf<String?>(null) }
    val expiry = expiryIso?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    var strike by rememberSaveable { mutableStateOf("") }
    var rightName by rememberSaveable { mutableStateOf(Right.PE.name) }
    val right = if (rightName == Right.CE.name) Right.CE else Right.PE
    var action by rememberSaveable { mutableStateOf("SELL") }
    var lots by rememberSaveable { mutableStateOf(1) }
    var type by rememberSaveable { mutableStateOf("MARKET") }
    var product by rememberSaveable { mutableStateOf("NRML") }
    var price by rememberSaveable { mutableStateOf("") }
    var trigger by rememberSaveable { mutableStateOf("") }
    var open by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(underlying) {
        expiries = model.paperExpiries(underlying)
        // Keep the expiry already picked when it is still listed.
        if (expiry == null || expiry !in expiries) expiryIso = expiries.firstOrNull()?.toString()
    }
    // Only listed strikes can be chosen: the ones around the index, nearest first in the middle.
    var listed by remember { mutableStateOf<List<Double>>(emptyList()) }
    var spot by remember { mutableStateOf<Double?>(null) }
    // Which underlying and expiry [listed] belongs to: nothing can be placed until it matches the form.
    var listedFor by remember { mutableStateOf<Pair<String, LocalDate>?>(null) }
    LaunchedEffect(underlying, expiry) {
        val e = expiry ?: return@LaunchedEffect
        val (ks, sp) = model.paperStrikes(underlying, e)
        listed = ks; spot = sp; listedFor = underlying to e
        val atm = sp?.let { x -> ks.minByOrNull { kotlin.math.abs(it - x) } }
        if (strike.toDoubleOrNull() !in ks) strike = atm?.let { com.optionslab.engine.fmtG(it) } ?: ""
    }
    // One tap, one order: the button stays off until the paper book reloads after it (or 8 s pass).
    val paperNow by model.paper.collectAsState()
    var placing by remember { mutableStateOf<Any?>(null) }
    LaunchedEffect(placing, paperNow) {
        val sent = placing ?: return@LaunchedEffect
        if (paperNow !== sent) { placing = null; return@LaunchedEffect }
        kotlinx.coroutines.delay(8_000)
        placing = null
    }
    LedgerCard(title = "Paper order") {
        if (!open) {
            BrassButton("New paper order", Modifier.fillMaxWidth(), tone = p.verdigris) { open = true }
        } else {
            ParamTokens("Underlying", listOf("NIFTY", "BANKNIFTY").map { it to (it == underlying) }) {
                val u = listOf("NIFTY", "BANKNIFTY")[it]
                // Nothing of the old index stays selected while the new one loads.
                if (u != underlying) { expiries = emptyList(); expiryIso = null; strike = ""; listed = emptyList(); spot = null; listedFor = null; underlying = u }
            }
            if (expiries.isEmpty()) Note("Loading the listed expiries from Upstox…")
            ParamTokens("Expiry", expiries.map { it.toString().substring(5) to (it == expiry) }) { expiryIso = expiries[it].toString() }
            ParamTokens("Option", listOf("PE" to (right == Right.PE), "CE" to (right == Right.CE))) { rightName = if (it == 0) Right.PE.name else Right.CE.name }
            ParamTokens("Side", listOf("SELL" to (action == "SELL"), "BUY" to (action == "BUY"))) { action = if (it == 0) "SELL" else "BUY" }
            val lotChoices = listOf(1, 2, 3, 5, 10)
            ParamTokens("Lots", lotChoices.map { "$it" to (it == lots) }) { lots = lotChoices[it] }
            val types = listOf("MARKET", "LIMIT", "SL", "SL-M")
            ParamTokens("Type", types.map { it to (it == type) }) { type = types[it] }
            ParamTokens("Product", listOf("NRML" to (product == "NRML"), "MIS" to (product == "MIS"))) { product = if (it == 0) "NRML" else "MIS" }
            com.optionslab.app.ui.components.StrikeDropdown(listed, spot, strike, underlying) { strike = it }
            if (type == "LIMIT" || type == "SL") OutlinedTextField(price, { price = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Price") },
                singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            if (type == "SL" || type == "SL-M") OutlinedTextField(trigger, { trigger = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Trigger") },
                singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // A LIMIT / SL needs its price, an SL / SL-M its trigger (above 0), as the chain's order sheet asks.
                val priced = (type != "LIMIT" && type != "SL" || price.toDoubleOrNull()?.let { it > 0 } == true) &&
                    (type != "SL" && type != "SL-M" || trigger.toDoubleOrNull()?.let { it > 0 } == true)
                val ready = expiry != null && listedFor == (underlying to expiry) && strike.toDoubleOrNull()?.let { it in listed } == true && priced
                BrassButton("Place paper order", Modifier.weight(1f), tone = p.verdigris, busy = placing != null, enabled = ready && placing == null) {
                    if (placing != null || !ready) return@BrassButton
                    placing = paperNow
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
    val owners by model.orderOwners.collectAsState()
    LedgerCard(title = "Paper positions") {
        Text("TODAY", style = Type.label.copy(color = p.inkSoft))
        RollingFigure(book.totalPnlToday, { rs(it, true) }, Type.figureLarge.copy(color = if (book.totalPnlToday >= 0) p.verdigris else p.oxblood), calm = true)
        LedgerLine("Unrealised", rs(book.totalUnrealizedPnl, true))
        LedgerLine("Realised today", rs(book.totalTodayRealizedPnl, true))
        if (book.positions.isEmpty()) Note("No paper positions.")
        book.positions.forEach { ps ->
            Rule(Modifier.padding(vertical = 6.dp))
            Row(Modifier.clickable { model.rowAction.value = RowTarget.PaperPosition(ps) }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    com.optionslab.app.ui.components.FitText(ps.symbol, style = Type.figure.copy(color = p.ink, fontSize = 14.sp))   // a symbol never wraps mid-word
                    Text("${ps.product} · ${if (ps.quantity > 0) "LONG" else if (ps.quantity < 0) "SHORT" else "CLOSED"} ${kotlin.math.abs(ps.quantity)}" +
                        if (ps.quantity != 0) " · avg ${px(ps.averagePrice)} → ${px(ps.ltp)}" else "", style = Type.figure.copy(color = p.inkSoft, fontSize = 11.sp))
                    com.optionslab.app.data.Origins.paperPosition(owners, v.trades, ps.symbol, ps.product, ps.quantity)?.let { SourcePill(com.optionslab.app.data.Origins.positionDisplay(it)) }
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
    val owners by model.orderOwners.collectAsState()
    val st = v.orders.statistics
    // Each filled order's P&L, marked at its contract's current price (the positions carry the live LTP).
    val ltp = v.positions.positions.associate { it.symbol to it.ltp }
    LedgerCard(title = "Paper order book") {
        LedgerLine("Buy / sell", "${st.totalBuyOrders} / ${st.totalSellOrders}")
        LedgerLine("Complete · open · pending · rejected", "${st.totalCompletedOrders} · ${st.totalOpenOrders} · ${st.totalTriggerPendingOrders} · ${st.totalRejectedOrders}")
        if (v.orders.orders.isEmpty()) Note("No paper orders this session.")
        v.orders.orders.forEach { o ->
            Rule(Modifier.padding(vertical = 5.dp))
            val tone = when (o.status) { "complete" -> p.verdigris; "rejected", "cancelled" -> p.oxblood; else -> p.amber }
            // The whole order (its three lines) is the tap target, as on the live order book: the first line alone was 15 dp.
            Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { model.rowAction.value = RowTarget.PaperOrder(o) }) {
                com.optionslab.app.ui.components.FitText("${o.action} ${o.symbol} ×${o.quantity}", style = Type.figure.copy(color = if (o.action == "SELL") p.oxblood else p.verdigris, fontSize = 13.sp))
                Text("${o.product} · ${o.priceType}${if (o.price > 0) " ${px(o.price)}" else ""}${if (o.triggerPrice > 0) " trg ${px(o.triggerPrice)}" else ""} · ${o.timestamp.takeLast(8)}",
                    style = Type.figure.copy(color = p.inkSoft, fontSize = 11.sp))
                // The status line carries the P&L at its end: the contract's name above keeps the whole width.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${o.status.uppercase()}${if (o.filledQuantity > 0) " · ${o.filledQuantity} @ ${px(o.averagePrice)}" else ""}${if (o.rejectionReason.isNotBlank()) " · ${o.rejectionReason}" else ""}",
                        style = Type.figure.copy(color = tone, fontSize = 11.sp), modifier = Modifier.weight(1f))
                    PnlFigure(fillPnl(o.action.equals("BUY", true), o.averagePrice, o.filledQuantity, ltp[o.symbol]), Modifier.padding(start = 8.dp))
                }
            }
            OrderSourcePill(owners, "paper:${o.orderId}")
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
                Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {  // scrolls at a large font / in landscape
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
private fun PaperTrades(model: AppModel, v: Paper.Snapshot) {
    val p = LocalPalette.current
    val owners by model.orderOwners.collectAsState()
    val ltp = v.positions.positions.associate { it.symbol to it.ltp }
    LedgerCard(title = "Paper trade book") {
        if (v.trades.isEmpty()) Note("No paper trades this session.")
        v.trades.forEachIndexed { i, t ->
            if (i > 0) Rule(Modifier.padding(vertical = 4.dp))
            Row(Modifier.clickable { model.rowAction.value = RowTarget.PaperTrade(t) }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    com.optionslab.app.ui.components.FitText("${t.action} ${t.symbol}", style = Type.figure.copy(color = if (t.action == "SELL") p.oxblood else p.verdigris, fontSize = 13.sp))
                    Text("${t.product} · ${t.timestamp.takeLast(8)}", style = Type.figure.copy(color = p.inkSoft, fontSize = 11.sp))
                    OrderSourcePill(owners, "paper:${t.orderId}")
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("${t.quantity} @ ${px(t.price)}", style = Type.figure.copy(color = p.ink, fontSize = 13.sp))
                    PnlFigure(fillPnl(t.action.equals("BUY", true), t.price, t.quantity, ltp[t.symbol]))
                }
            }
        }
    }
}

@Composable
private fun PaperFunds(model: AppModel, v: Paper.Snapshot, onReset: () -> Unit) {
    val p = LocalPalette.current
    val f = v.funds
    var toDefault by remember { mutableStateOf(false) }
    if (toDefault) PaperDefaultDialog(model) { toDefault = false }
    LedgerCard(title = "Paper funds") {
        LedgerLine("Available", rs(f.availableCash), p.verdigris)
        LedgerLine("Used margin", rs(f.utilisedDebits))
        LedgerLine("Unrealised M2M", rs(f.m2mUnrealized, true))
        LedgerLine("Realised today", rs(f.todayRealizedPnl, true))
        LedgerLine("Realised, all time", rs(f.totalRealizedPnl, true))
        LedgerLine("Total P&L", rs(f.totalPnl, true), if (f.totalPnl >= 0) p.verdigris else p.oxblood)
        LedgerLine("Resets", "${f.resetCount} · last ${f.lastReset.take(10)}")
        Spacer(Modifier.height(8.dp))
        BrassButton("Set paper amount / reset", Modifier.fillMaxWidth(), tone = p.oxblood, onClick = onReset)
        Spacer(Modifier.height(8.dp))
        BrassButton("Reset paper to default", Modifier.fillMaxWidth(), tone = p.oxblood) { toDefault = true }
    }
}

/** The paper money, always in view: what is free to trade, what is in use, and the P&L. */
@Composable
private fun PaperBalance(v: Paper.Snapshot, onChange: () -> Unit) {
    val p = LocalPalette.current
    val f = v.funds
    LedgerCard {
        Text("Paper balance", style = Type.label.copy(color = p.inkSoft, fontSize = 13.sp))
        RollingFigure(f.availableCash, { rs(it) }, Type.figureLarge.copy(color = p.ink))
        Text("available to trade", style = Type.bodySmall.copy(color = p.inkSoft))
        Spacer(Modifier.height(8.dp))
        LedgerLine("Used margin", rs(f.utilisedDebits))
        LedgerLine("Total P&L", rs(f.totalPnl, true), if (f.totalPnl >= 0) p.verdigris else p.oxblood)
        Spacer(Modifier.height(8.dp))
        BrassButton("Set paper amount", Modifier.fillMaxWidth(), tone = p.ink, onClick = onChange)
    }
}

/** Confirm resetting everything on paper (never Zerodha) to how the app first starts. */
@Composable
fun PaperDefaultDialog(model: AppModel, onClose: () -> Unit) {
    val default = com.optionslab.engine.sandbox.SandboxConfig().startingCapital.toDouble()
    AlertDialog(
        onDismissRequest = onClose, properties = secure,
        title = { Text("Reset paper to default?", style = Type.title) },
        text = {
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                Text("Paper only - nothing at Zerodha changes.", style = Type.body)
                Spacer(Modifier.height(6.dp))
                Text("The paper account starts again with ${rs(default)}. Every paper order, trade and position is cleared, " +
                    "the paper P&L calendar is emptied, and the arms, strategies, Pine scripts, stops and journal notes " +
                    "forget their paper trades. ORB arms not armed for Zerodha are switched off.", style = Type.bodySmall)
            }
        },
        confirmButton = { TextButton({ model.paperResetAll(); onClose() }) { Text("Reset paper") } },
        dismissButton = { TextButton(onClose) { Text("Keep") } },
    )
}

/** Starting capital for a fresh paper account: a preset, or any amount typed in. */
@Composable
fun PaperResetDialog(model: AppModel, onClose: () -> Unit) {
    val p = LocalPalette.current
    val choices = listOf(100_000.0, 500_000.0, 1_000_000.0, 10_000_000.0)
    var pick by remember { mutableStateOf(1_000_000.0) }
    var typed by remember { mutableStateOf("") }
    val custom = typed.toDoubleOrNull()
    val amount = if (typed.isNotEmpty()) custom else pick
    val valid = amount != null && amount >= 10_000.0 && amount <= 1_000_000_000.0
    AlertDialog(
        onDismissRequest = onClose, properties = secure,
        title = { Text("Set paper amount", style = Type.title) },
        text = {
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {  // scrolls at a large font / in landscape
                Text("The paper account starts again with this amount. Every paper order, trade and position is cleared.", style = Type.bodySmall)
                ParamTokens("Amount", choices.map { rs(it) to (typed.isEmpty() && it == pick) }) { pick = choices[it]; typed = "" }
                OutlinedTextField(typed, { typed = it.filter(Char::isDigit).take(10) }, label = { Text("Or type an amount (Rs)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                if (typed.isNotEmpty() && !valid) Text("Enter between Rs 10,000 and Rs 100,00,00,000.", style = Type.bodySmall.copy(color = p.oxblood))
            }
        },
        confirmButton = { TextButton({ if (valid) { model.paperReset(amount!!); onClose() } }, enabled = valid) { Text("Start with ${if (valid) rs(amount!!) else "…"}") } },
        dismissButton = { TextButton(onClose) { Text("Keep") } },
    )
}
