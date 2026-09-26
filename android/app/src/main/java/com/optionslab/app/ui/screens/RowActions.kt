package com.optionslab.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import com.optionslab.app.ui.components.AlertDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.optionslab.app.data.Broker
import com.optionslab.app.ui.AppModel
import com.optionslab.app.ui.Load
import com.optionslab.app.ui.components.BrassButton
import com.optionslab.app.ui.components.LedgerLine
import com.optionslab.app.ui.components.Note
import com.optionslab.app.ui.rs
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import java.util.Locale
import kotlin.math.abs

/** What a tap on an order, position or trade row refers to, anywhere in the app. */
sealed interface RowTarget {
    data class PaperPosition(val row: com.optionslab.engine.sandbox.PositionRow) : RowTarget
    data class PaperOrder(val row: com.optionslab.engine.sandbox.OrderRow) : RowTarget
    data class PaperTrade(val row: com.optionslab.engine.sandbox.TradeRow) : RowTarget
    data class LivePosition(val row: Broker.Position) : RowTarget
    data class LiveOrder(val row: Broker.OrderRow) : RowTarget
    data class LiveTrade(val row: Broker.Trade) : RowTarget
}

private fun px(x: Double) = String.format(Locale.ENGLISH, "%,.2f", x)
private val PAPER_WORKING = setOf("open", "trigger pending", "pending")

/**
 * The popup a tap on any order / position / trade row opens, on every page:
 * close a position, cancel or modify a working order, or, for a finished
 * order or trade, close the position it left open. Live actions keep the
 * app's rules: a square-off opens the usual review, a cancel asks for the
 * PIN or fingerprint.
 */
@Composable
fun RowActionPopup(model: AppModel) {
    val target by model.rowAction.collectAsState()
    val t = target ?: return
    val p = LocalPalette.current
    val owners by model.orderOwners.collectAsState()
    val paper by model.paper.collectAsState()
    val account by model.account.collectAsState()
    var modify by remember { mutableStateOf<Broker.OrderRow?>(null) }
    var cancelAuth by remember { mutableStateOf<Broker.OrderRow?>(null) }
    var protectFor by remember { mutableStateOf<ProtectTarget?>(null) }
    var journalFor by remember { mutableStateOf<Pair<String, String>?>(null) }
    val protections by model.protections.collectAsState()
    LaunchedEffect(t) { model.refreshProtections() }
    fun close() { model.rowAction.value = null }

    // The open position a finished order or trade belongs to, so it can be closed from here too.
    val paperOpen = (paper as? Load.Done)?.value?.positions?.positions.orEmpty().filter { it.quantity != 0 }
    val liveOpen = (account as? Load.Done)?.value?.positions.orEmpty().filter { it.qty != 0 }

    val title: String
    val lines = ArrayList<Pair<String, String>>()
    var source: Pair<String, Boolean>? = null
    /** label, tone, slide-to-confirm (closing/cancelling) or a plain button (modify), action. */
    data class Act(val label: String, val tone: androidx.compose.ui.graphics.Color, val swipe: Boolean, val run: () -> Unit)
    val actions = ArrayList<Act>()
    fun swipe(label: String, run: () -> Unit) { actions += Act(label, p.oxblood, true, run) }
    fun button(label: String, run: () -> Unit) { actions += Act(label, p.ink, false, run) }
    var pnl: Double? = null
    when (t) {
        is RowTarget.PaperPosition -> {
            val r = t.row
            title = r.symbol
            pnl = r.totalPnlToday
            lines += "Account" to "Paper"
            lines += "Position" to "${if (r.quantity > 0) "LONG" else if (r.quantity < 0) "SHORT" else "CLOSED"} ${abs(r.quantity)}"
            lines += "Product · exchange" to "${r.product} · ${r.exchange}"
            lines += "Average price" to px(r.averagePrice)
            lines += "Last price (LTP)" to px(r.ltp)
            lines += "Unrealised P&L" to rs(r.unrealizedPnl, true)
            lines += "Realised today" to rs(r.todayRealizedPnl, true)
            lines += "Change" to String.format(Locale.ENGLISH, "%+.2f%%", r.pnlPercent)
            if (r.quantity != 0) {
                val pr = protections.lastOrNull { !it.live && it.symbol == r.symbol }
                pr?.let { lines += "Protection" to it.describe() }
                button(if (pr == null) "Protect: stop · trail · target" else "Change protection") {
                    protectFor = ProtectTarget(false, r.symbol, r.exchange, r.product, r.quantity, r.ltp)
                }
                pr?.let { button("Remove protection") { model.removeProtection(it.id) } }
                swipe("Slide to close position") { model.paperClose(r.symbol, r.product); close() }
            }
        }
        is RowTarget.PaperOrder -> {
            val r = t.row
            title = "${r.action} ${r.symbol}"
            val pos = paperOpen.firstOrNull { it.symbol == r.symbol && it.product == r.product }
            lines += "Account" to "Paper"
            lines += "Status" to r.status.uppercase() + if (r.rejectionReason.isNotBlank()) " · ${r.rejectionReason}" else ""
            lines += "Quantity · filled · pending" to "${r.quantity} · ${r.filledQuantity} · ${r.pendingQuantity}"
            lines += "Order type" to "${r.priceType}${if (r.price > 0) " @ ${px(r.price)}" else ""}${if (r.triggerPrice > 0) " · trigger ${px(r.triggerPrice)}" else ""}"
            lines += "Average fill" to (r.averagePrice.takeIf { it > 0 }?.let(::px) ?: "—")
            lines += "Product · exchange" to "${r.product} · ${r.exchange}"
            lines += "Placed at" to r.timestamp.takeLast(8)
            lines += "Order id" to r.orderId
            if (pos != null && r.averagePrice > 0 && r.filledQuantity > 0) {
                pnl = (pos.ltp - r.averagePrice) * r.filledQuantity * (if (r.action == "BUY") 1 else -1)
                lines += "Last price (LTP)" to px(pos.ltp)
            }
            source = orderSource(owners, "paper:${r.orderId}")
            if (r.status.lowercase() in PAPER_WORKING) swipe("Slide to cancel order") { model.paperCancel(r.orderId); close() }
            pos?.let { ps -> swipe("Slide to close position (${abs(ps.quantity)})") { model.paperClose(ps.symbol, ps.product); close() } }
        }
        is RowTarget.PaperTrade -> {
            val r = t.row
            title = "${r.action} ${r.symbol}"
            val pos = paperOpen.firstOrNull { it.symbol == r.symbol && it.product == r.product }
            lines += "Account" to "Paper"
            lines += "Filled" to "${r.quantity} @ ${px(r.price)}"
            lines += "Trade value" to rs(r.tradeValue)
            if (r.charges > 0) lines += "Charges (brokerage, STT, fees)" to rs(r.charges)
            lines += "Product · exchange" to "${r.product} · ${r.exchange}"
            lines += "Time" to r.timestamp.takeLast(8)
            lines += "Trade · order id" to "${r.tradeId} · ${r.orderId}"
            val jKey = "paper:${r.tradeId}"
            com.optionslab.app.data.Journal.of(jKey)?.let { e -> lines += "Journal" to (e.tags.joinToString() + if (e.note.isNotBlank()) " · ${e.note}" else "") }
            button("Journal: note · tags") { journalFor = jKey to "${r.action} ${r.symbol}" }
            if (pos != null) {
                pnl = (pos.ltp - r.price) * r.quantity * (if (r.action == "BUY") 1 else -1)
                lines += "Last price (LTP)" to px(pos.ltp)
            }
            source = orderSource(owners, "paper:${r.orderId}")
            pos?.let { ps -> swipe("Slide to close position (${abs(ps.quantity)})") { model.paperClose(ps.symbol, ps.product); close() } }
        }
        is RowTarget.LivePosition -> {
            val r = t.row
            title = r.symbol
            pnl = r.pnl
            lines += "Account" to "Zerodha (live)"
            lines += "Position" to "${if (r.qty > 0) "LONG" else if (r.qty < 0) "SHORT" else "CLOSED"} ${abs(r.qty)}" + if (r.overnight != 0) " (overnight ${r.overnight})" else ""
            lines += "Product · exchange" to "${r.product} · ${r.exchange}"
            lines += "Average price" to px(r.avg)
            lines += "Last price (LTP)" to px(r.last)
            lines += "Unrealised · realised" to "${rs(r.unrealised, true)} · ${rs(r.realised, true)}"
            lines += "M2M" to rs(r.m2m, true)
            lines += "Bought · sold today" to "${r.buyQty} @ ${px(r.buyAvg)} · ${r.sellQty} @ ${px(r.sellAvg)}"
            if (r.qty != 0) {
                val pr = protections.lastOrNull { it.live && it.symbol == r.symbol }
                pr?.let { lines += "Protection" to it.describe() }
                button(if (pr == null) "Protect: stop · trail · target" else "Change protection") {
                    protectFor = ProtectTarget(true, r.symbol, r.exchange, r.product, r.qty, r.last)
                }
                pr?.let { button("Remove protection") { model.removeProtection(it.id) } }
                swipe("Slide to close position") { model.planSquareOff(r); close() }
            }
        }
        is RowTarget.LiveOrder -> {
            val r = t.row
            title = "${r.side} ${r.symbol}"
            val pos = liveOpen.firstOrNull { it.symbol == r.symbol && it.product == r.product }
            lines += "Account" to "Zerodha (live)"
            lines += "Status" to r.status + if (r.message.isNotBlank()) " · ${r.message}" else ""
            lines += "Quantity · filled · pending" to "${r.qty} · ${r.filled} · ${r.pending}"
            lines += "Order type" to "${r.type}${if (r.price > 0) " @ ${px(r.price)}" else ""}${if (r.trigger > 0) " · trigger ${px(r.trigger)}" else ""}"
            lines += "Average fill" to (r.avg.takeIf { it > 0 }?.let(::px) ?: "—")
            lines += "Product · exchange · variety" to "${r.product} · ${r.exchange} · ${r.variety}"
            lines += "Placed at" to r.placedAt.takeLast(8)
            lines += "Order id" to r.id
            if (pos != null && r.avg > 0 && r.filled > 0) {
                pnl = (pos.last - r.avg) * r.filled * (if (r.side == "BUY") 1 else -1)
                lines += "Last price (LTP)" to px(pos.last)
            }
            source = orderSource(owners, "kite:${r.id}", r.tag)
            if (r.working) {
                button("Modify order") { modify = r }
                swipe("Slide to cancel order") { cancelAuth = r }
            }
            pos?.let { ps -> swipe("Slide to close position (${abs(ps.qty)})") { model.planSquareOff(ps); close() } }
        }
        is RowTarget.LiveTrade -> {
            val r = t.row
            title = "${r.side} ${r.symbol}"
            val pos = liveOpen.firstOrNull { it.symbol == r.symbol && it.product == r.product }
            lines += "Account" to "Zerodha (live)"
            lines += "Filled" to "${r.qty} @ ${px(r.price)}"
            lines += "Trade value" to rs(r.qty * r.price)
            lines += "Product · exchange" to "${r.product} · ${r.exchange}"
            lines += "Time" to r.at.takeLast(8)
            lines += "Trade · order id" to "${r.id} · ${r.orderId}"
            val jKey = "kite:${r.id}"
            com.optionslab.app.data.Journal.of(jKey)?.let { e -> lines += "Journal" to (e.tags.joinToString() + if (e.note.isNotBlank()) " · ${e.note}" else "") }
            button("Journal: note · tags") { journalFor = jKey to "${r.side} ${r.symbol}" }
            if (pos != null) {
                pnl = (pos.last - r.price) * r.qty * (if (r.side == "BUY") 1 else -1)
                lines += "Last price (LTP)" to px(pos.last)
            }
            source = orderSource(owners, "kite:${r.orderId}", (account as? Load.Done)?.value?.orders?.firstOrNull { it.id == r.orderId }?.tag.orEmpty())
            pos?.let { ps -> swipe("Slide to close position (${abs(ps.qty)})") { model.planSquareOff(ps); close() } }
        }
    }

    if (modify == null && cancelAuth == null && protectFor == null && journalFor == null) AlertDialog(
        onDismissRequest = ::close,
        properties = DialogProperties(securePolicy = com.optionslab.app.security.Capture.policy),
        title = { Text(title, style = Type.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                // P&L first and large: what this order or position is making right now.
                pnl?.let { v ->
                    Text("P&L", style = Type.label.copy(color = p.inkSoft, fontSize = 12.sp))
                    Text(rs(v, true), style = Type.figureLarge.copy(color = if (v >= 0) p.verdigris else p.oxblood))
                    Spacer(Modifier.height(6.dp))
                }
                lines.forEach { (k, v) -> LedgerLine(k, v) }
                source?.let { (label, _) -> LedgerLine("Placed by", label.removePrefix("Strategy: ")) }
                if (actions.isEmpty()) Note("Nothing to close or cancel: this order is finished and no position is open from it.", Modifier.padding(top = 8.dp))
                Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    actions.forEach { a ->
                        if (a.swipe) com.optionslab.app.ui.components.SwipeToConfirm(a.label, a.tone, onConfirm = a.run)
                        else BrassButton(a.label, Modifier.fillMaxWidth(), tone = a.tone, onClick = a.run)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(::close) { Text("Close") } },
    )
    modify?.let { o -> ModifyDialog(model, o) { modify = null; close() } }
    protectFor?.let { pt -> ProtectDialog(model, pt) { done -> protectFor = null; if (done) close() } }
    journalFor?.let { (key, label) -> JournalDialog(key, label) { journalFor = null } }
    // Cancelling a working order (it may be a stop-loss) is proved like a send.
    cancelAuth?.let { o -> Reauth(model, onOk = { cancelAuth = null; model.cancelOrder(o.id, o.variety); close() }, onCancel = { cancelAuth = null }) }
}


/** An open position to protect. [qty] is signed: + long, - short. */
data class ProtectTarget(val live: Boolean, val symbol: String, val exchange: String, val product: String, val qty: Int, val price: Double)

/**
 * Stop, trailing stop and target for one position. Paper: set at once. Zerodha: the
 * PIN or fingerprint first (once); after that the trailing stop moves by itself,
 * and only ever tighter.
 */
@Composable
fun ProtectDialog(model: AppModel, t: ProtectTarget, onDone: (Boolean) -> Unit) {
    val p = LocalPalette.current
    var stop by remember { mutableStateOf("") }
    var trail by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }
    var auth by remember { mutableStateOf(false) }
    val long = t.qty > 0
    val spec = com.optionslab.app.ui.ProtectSpec(stop.toDoubleOrNull(), trail.toDoubleOrNull(), target.toDoubleOrNull())
    val problem = com.optionslab.engine.risk.Protection.validate(if (long) 1 else -1, t.price, spec.stop, spec.trail, spec.target)
    fun go() = model.protect(t.live, t.symbol, t.exchange, t.product, t.qty, t.price, spec).also { onDone(true) }
    if (auth) { Reauth(model, onOk = { auth = false; go() }, onCancel = { auth = false }); return }
    AlertDialog(
        onDismissRequest = { onDone(false) },
        properties = DialogProperties(securePolicy = com.optionslab.app.security.Capture.policy),
        title = { Text("Protect ${t.symbol}", style = Type.title) },
        text = {
            Column {
                Text("${if (long) "Long" else "Short"} ${abs(t.qty)} · last ${px(t.price)} · ${if (t.live) "Zerodha" else "paper"}", style = Type.bodySmall.copy(color = p.inkSoft))
                PriceField(stop, { stop = it }, "Stop price (${if (long) "below" else "above"} ${px(t.price)})")
                PriceField(trail, { trail = it }, "…or trail by (points)")
                PriceField(target, { target = it }, "Target price (optional)")
                Note(if (spec.trail != null) "Trailing: the stop follows the best price at ${spec.trail} points behind and never loosens." +
                    (if (spec.stop != null) " It starts at your stop price." else "")
                    else "The stop rests as an SL-M exit and the target as a LIMIT exit; when one fills the other is cancelled.", Modifier.padding(top = 8.dp))
                if (t.live) Note("Zerodha: real exit orders are placed now. You confirm once with your PIN or fingerprint.", Modifier.padding(top = 4.dp))
            }
        },
        confirmButton = {
            TextButton({
                if (problem != null) com.optionslab.app.work.Alerts.error(problem)
                else if (t.live) auth = true else go()
            }) { Text(if (t.live) "Confirm & protect" else "Protect") }
        },
        dismissButton = { TextButton({ onDone(false) }) { Text("Cancel") } },
    )
}

@Composable
fun PriceField(v: String, set: (String) -> Unit, label: String) {
    androidx.compose.material3.OutlinedTextField(v, { set(it.filter { c -> c.isDigit() || c == '.' }.take(10)) }, label = { Text(label) }, singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal))
}


/** A note and tags on one trade (setup, mistakes, mood); the P&L tab adds up what each tag makes. */
@Composable
fun JournalDialog(key: String, label: String, onClose: () -> Unit) {
    val p = LocalPalette.current
    val j = com.optionslab.app.data.Journal
    val start = remember(key) { j.of(key) }
    var note by remember(key) { mutableStateOf(start?.note.orEmpty()) }
    var tags by remember(key) { mutableStateOf(start?.tags.orEmpty()) }
    AlertDialog(
        onDismissRequest = onClose,
        properties = DialogProperties(securePolicy = com.optionslab.app.security.Capture.policy),
        title = { Text("Journal · $label", style = Type.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                listOf("Setup" to j.SETUPS, "Mistakes" to j.MISTAKES, "Mood" to j.MOODS).forEach { (head, list) ->
                    Text(head, style = Type.label.copy(color = p.inkSoft, fontSize = 11.sp), modifier = Modifier.padding(top = 8.dp))
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        list.forEach { t ->
                            val on = t in tags
                            Text(t, style = Type.label.copy(color = if (on) p.paper else p.ink, fontSize = 11.sp),
                                modifier = Modifier.padding(top = 4.dp).background(if (on) p.ink else p.chip, androidx.compose.foundation.shape.RoundedCornerShape(50))
                                    .clickable { tags = if (on) tags - t else tags + t }.padding(horizontal = 10.dp, vertical = 5.dp))
                        }
                    }
                }
                androidx.compose.material3.OutlinedTextField(note, { note = it.take(500) }, label = { Text("Note") },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
            }
        },
        confirmButton = { TextButton({ j.put(key, note, tags); com.optionslab.app.work.Alerts.success("Journal saved."); onClose() }) { Text("Save") } },
        dismissButton = { TextButton(onClose) { Text("Cancel") } },
    )
}
