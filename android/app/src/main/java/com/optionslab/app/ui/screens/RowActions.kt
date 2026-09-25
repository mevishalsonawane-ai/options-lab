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
import androidx.compose.material3.AlertDialog
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
            if (r.quantity != 0) swipe("Slide to close position") { model.paperClose(r.symbol, r.product); close() }
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
            lines += "Product · exchange" to "${r.product} · ${r.exchange}"
            lines += "Time" to r.timestamp.takeLast(8)
            lines += "Trade · order id" to "${r.tradeId} · ${r.orderId}"
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
            if (r.qty != 0) swipe("Slide to close position") { model.planSquareOff(r); close() }
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
            if (pos != null) {
                pnl = (pos.last - r.price) * r.qty * (if (r.side == "BUY") 1 else -1)
                lines += "Last price (LTP)" to px(pos.last)
            }
            source = orderSource(owners, "kite:${r.orderId}", (account as? Load.Done)?.value?.orders?.firstOrNull { it.id == r.orderId }?.tag.orEmpty())
            pos?.let { ps -> swipe("Slide to close position (${abs(ps.qty)})") { model.planSquareOff(ps); close() } }
        }
    }

    if (modify == null && cancelAuth == null) AlertDialog(
        onDismissRequest = ::close,
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
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
    // Cancelling a working order (it may be a stop-loss) is proved like a send.
    cancelAuth?.let { o -> Reauth(model, onOk = { cancelAuth = null; model.cancelOrder(o.id, o.variety); close() }, onCancel = { cancelAuth = null }) }
}
