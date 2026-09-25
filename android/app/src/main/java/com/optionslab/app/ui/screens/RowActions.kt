package com.optionslab.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
    val actions = ArrayList<Triple<String, androidx.compose.ui.graphics.Color, () -> Unit>>()
    when (t) {
        is RowTarget.PaperPosition -> {
            val r = t.row
            title = r.symbol
            lines += "Position" to "${if (r.quantity > 0) "LONG" else "SHORT"} ${abs(r.quantity)} · ${r.product}"
            lines += "Average → LTP" to "${px(r.averagePrice)} → ${px(r.ltp)}"
            lines += "P&L today" to rs(r.totalPnlToday, true)
            if (r.quantity != 0) actions += Triple("Close position (market)", p.oxblood) { model.paperClose(r.symbol, r.product); close() }
        }
        is RowTarget.PaperOrder -> {
            val r = t.row
            title = "${r.action} ${r.symbol}"
            lines += "Quantity" to "${r.quantity} (filled ${r.filledQuantity})"
            lines += "Type" to "${r.priceType}${if (r.price > 0) " @ ${px(r.price)}" else ""}${if (r.triggerPrice > 0) " · trigger ${px(r.triggerPrice)}" else ""} · ${r.product}"
            lines += "Status" to r.status.uppercase() + if (r.rejectionReason.isNotBlank()) " · ${r.rejectionReason}" else ""
            source = orderSource(owners, "paper:${r.orderId}")
            if (r.status.lowercase() in PAPER_WORKING) actions += Triple("Cancel order", p.oxblood) { model.paperCancel(r.orderId); close() }
            paperOpen.firstOrNull { it.symbol == r.symbol && it.product == r.product }?.let { ps ->
                actions += Triple("Close position (${abs(ps.quantity)})", p.oxblood) { model.paperClose(ps.symbol, ps.product); close() }
            }
        }
        is RowTarget.PaperTrade -> {
            val r = t.row
            title = "${r.action} ${r.symbol}"
            lines += "Filled" to "${r.quantity} @ ${px(r.price)} · ${r.product}"
            lines += "Time" to r.timestamp.takeLast(8)
            source = orderSource(owners, "paper:${r.orderId}")
            paperOpen.firstOrNull { it.symbol == r.symbol && it.product == r.product }?.let { ps ->
                actions += Triple("Close position (${abs(ps.quantity)})", p.oxblood) { model.paperClose(ps.symbol, ps.product); close() }
            }
        }
        is RowTarget.LivePosition -> {
            val r = t.row
            title = r.symbol
            lines += "Position" to "${if (r.qty > 0) "LONG" else "SHORT"} ${abs(r.qty)} · ${r.product} · ${r.exchange}"
            lines += "Average → LTP" to "${px(r.avg)} → ${px(r.last)}"
            lines += "P&L" to rs(r.pnl, true)
            if (r.qty != 0) actions += Triple("Close position (square off)", p.oxblood) { model.planSquareOff(r); close() }
        }
        is RowTarget.LiveOrder -> {
            val r = t.row
            title = "${r.side} ${r.symbol}"
            lines += "Quantity" to "${r.qty} (filled ${r.filled})"
            lines += "Type" to "${r.type}${if (r.price > 0) " @ ${px(r.price)}" else ""}${if (r.trigger > 0) " · trigger ${px(r.trigger)}" else ""} · ${r.product}"
            lines += "Status" to r.status + if (r.message.isNotBlank()) " · ${r.message}" else ""
            source = orderSource(owners, "kite:${r.id}", r.tag)
            if (r.working) {
                actions += Triple("Cancel order", p.oxblood) { cancelAuth = r }
                actions += Triple("Modify order", p.ink) { modify = r }
            }
            liveOpen.firstOrNull { it.symbol == r.symbol && it.product == r.product }?.let { ps ->
                actions += Triple("Close position (${abs(ps.qty)})", p.oxblood) { model.planSquareOff(ps); close() }
            }
        }
        is RowTarget.LiveTrade -> {
            val r = t.row
            title = "${r.side} ${r.symbol}"
            lines += "Filled" to "${r.qty} @ ${px(r.price)} · ${r.product}"
            lines += "Time" to r.at.takeLast(8)
            source = orderSource(owners, "kite:${r.orderId}", (account as? Load.Done)?.value?.orders?.firstOrNull { it.id == r.orderId }?.tag.orEmpty())
            liveOpen.firstOrNull { it.symbol == r.symbol && it.product == r.product }?.let { ps ->
                actions += Triple("Close position (${abs(ps.qty)})", p.oxblood) { model.planSquareOff(ps); close() }
            }
        }
    }

    if (modify == null && cancelAuth == null) AlertDialog(
        onDismissRequest = ::close,
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text(title, style = Type.title) },
        text = {
            Column {
                lines.forEach { (k, v) -> LedgerLine(k, v) }
                source?.let { (label, _) -> LedgerLine("Placed by", label.removePrefix("Strategy: ")) }
                if (actions.isEmpty()) Note("Nothing to close or cancel: this order is finished and no position is open from it.", Modifier.padding(top = 8.dp))
                Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    actions.forEach { (label, tone, act) -> BrassButton(label, Modifier.fillMaxWidth(), tone = tone, onClick = act) }
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
