package com.optionslab.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import com.optionslab.app.ui.components.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import com.optionslab.app.data.Ledger
import com.optionslab.app.ui.AppModel
import com.optionslab.app.ui.Load
import com.optionslab.app.ui.Page
import com.optionslab.app.ui.components.BrassButton
import com.optionslab.app.ui.components.FullSpinner
import com.optionslab.app.ui.components.LedgerCard
import com.optionslab.app.ui.components.LedgerLine
import com.optionslab.app.ui.components.Note
import com.optionslab.app.ui.components.RollingFigure
import com.optionslab.app.ui.components.Rule
import com.optionslab.app.ui.components.Stamp
import com.optionslab.app.ui.num
import com.optionslab.app.ui.rs
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import com.optionslab.engine.Live
import com.optionslab.engine.fmtG
import java.time.LocalDate

@Composable
fun TicketScreen(model: AppModel) {
    val p = LocalPalette.current
    val s by model.settings.collectAsState()
    val draft by model.draft.collectAsState()
    val ledger by model.ledger.collectAsState()
    val mark by model.openMark.collectAsState()
    var settling by remember { mutableStateOf<LocalDate?>(null) }
    var deleting by remember { mutableStateOf<LocalDate?>(null) }
    val broker by model.broker.collectAsState()
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri -> uri?.let(model::exportLedger) }

    Page {
        item {
            LedgerCard(title = "Expiry Put · today's trade") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Note("The strategy sells an out-of-the-money put on expiry day at 11:01 and holds it to the 15:30 settlement. This is the trade it would make right now; nothing is sent from here.", Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Stamp("Nothing is sent", p.oxblood, angle = 6f, animate = false)
                }
                ParamTokens("Underlying", listOf("NIFTY", "BANKNIFTY").map { it to (it == s.ticketUnderlying) }) { i -> model.update { it.copy(ticketUnderlying = listOf("NIFTY", "BANKNIFTY")[i]) } }
                ParamTokens("Lots", (1..4).map { "$it" to (it == s.ticketLots) }) { i -> model.update { it.copy(ticketLots = i + 1) } }
                val wings = listOf<Double?>(null, 0.0075, 0.025)
                ParamTokens("Structure", wings.map { (if (it == null) "naked" else "wing +${"%.2f".format(100 * it)}%") to (it == s.wingPct) }) { i -> model.update { it.copy(wingPct = wings[i]) } }
                Spacer(Modifier.height(12.dp))
                BrassButton("Price the live chain", Modifier.fillMaxWidth(), busy = draft is Load.Busy) { model.draftTicket() }
            }
        }
        when (val d = draft) {
            is Load.Busy -> item { LedgerCard { FullSpinner(d.label) } }
            is Load.Failed -> item { com.optionslab.app.ui.components.AlertOn(d.why) }
            is Load.Done -> item {
                Receipt(d.value.ticket)
                if (d.value.source.isNotBlank()) Note("Priced from ${d.value.source}.", Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(10.dp))
                BrassButton("Record as paper", Modifier.fillMaxWidth(), tone = p.verdigris) { model.recordDraft() }
            }
            Load.Idle -> Unit
        }
        item {
            LedgerCard(title = "Paper trades") {
                if (ledger.isEmpty()) Note("No tickets yet. Each expiry you record here builds a forward record - the only evidence that is not a backtest of itself.")
                ledger.forEachIndexed { i, e ->
                    if (i > 0) Rule(Modifier.padding(vertical = 8.dp))
                    LedgerRow(e, if (e.row.status == "open") mark else null, onSettle = { settling = e.row.ticket.session }, onDelete = { deleting = e.row.ticket.session })
                    if (e.row.status == "open" && !e.live && e.row.ticket.session == com.optionslab.app.data.Market.today()) {
                        if (broker.loggedIn) BrassButton("Send to Zerodha…", Modifier.fillMaxWidth().padding(top = 6.dp), tone = p.oxblood) { model.planTicket(e) }
                        else if (broker.configured) BrassButton("Log in to Zerodha to send it", Modifier.fillMaxWidth().padding(top = 6.dp), tone = p.inkSoft) { model.startKiteLogin() }
                    }
                }
                if (ledger.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    BrassButton("Export ledger (CSV)", Modifier.fillMaxWidth(), tone = p.inkSoft) { export.launch("paper_ledger.csv") }
                }
            }
        }
    }

    settling?.let { day ->
        var manual by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { settling = null },
            properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
            title = { Text("Settle $day", style = Type.title) },
            text = {
                Column {
                    Text("Cash settlement is the average of spot over 15:00-15:29, read from the index feed. Leave blank to fetch it, or enter the exchange's figure.", style = Type.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(manual, { manual = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Settlement price (optional)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                }
            },
            confirmButton = { TextButton({ model.settle(day, manual.toDoubleOrNull()); settling = null }) { Text("Settle") } },
            dismissButton = { TextButton({ settling = null }) { Text("Cancel") } },
        )
    }
    deleting?.let { day ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
            title = { Text("Strike out $day?", style = Type.title) },
            text = { Text("The ticket is removed from the paper ledger. A deleted loss is still a loss you would have taken; this is for mistakes only.", style = Type.bodySmall) },
            confirmButton = { TextButton({ model.deleteTicket(day); deleting = null }) { Text("Strike out") } },
            dismissButton = { TextButton({ deleting = null }) { Text("Keep") } },
        )
    }
}

/** The ticket, typed on a receipt slip. */
@Composable
fun Receipt(t: Live.Ticket) {
    val p = LocalPalette.current
    Box(Modifier.fillMaxWidth().background(p.card, RoundedCornerShape(14.dp)).border(1.dp, p.rule, RoundedCornerShape(14.dp)).padding(16.dp)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("ORDER SLIP", style = Type.label.copy(color = p.inkSoft), modifier = Modifier.weight(1f))
                Stamp("Paper", p.oxblood)
            }
            Spacer(Modifier.height(6.dp))
            Text(t.format(), style = Type.figure.copy(color = p.ink, fontSize = 13.sp, lineHeight = 19.sp))
        }
    }
}

@Composable
private fun LedgerRow(e: Ledger.Entry, liveMark: Double?, onSettle: () -> Unit, onDelete: () -> Unit) {
    val p = LocalPalette.current
    val t = e.row.ticket
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("${t.session}  ${t.underlying} ${fmtG(t.strike)} PE", style = Type.figure.copy(color = p.ink, fontSize = 14.sp))
            Text("credit ${"%.2f".format(t.credit)} · breakeven ${num(t.breakeven)}" + (t.wingStrike?.let { " · wing ${fmtG(it)}" } ?: ""),
                style = Type.figure.copy(color = p.inkSoft, fontSize = 12.sp))
        }
        val tr = e.row.trade
        when {
            tr != null -> Column(horizontalAlignment = Alignment.End) {
                Stamp(if (tr.won) "Win" else "Loss", if (tr.won) p.verdigris else p.oxblood, animate = false)
                Text(rs(tr.netPnl, true), style = Type.figure.copy(color = if (tr.won) p.verdigris else p.oxblood))
            }
            liveMark != null -> Column(horizontalAlignment = Alignment.End) {
                Text("LIVE", style = Type.label.copy(color = p.inkSoft, fontSize = 9.sp))
                RollingFigure(liveMark, { rs(it, true) }, Type.figure.copy(color = if (liveMark >= 0) p.verdigris else p.oxblood))
            }
            else -> Stamp(if (e.live) "Live" else "Open", if (e.live) p.oxblood else p.brass, animate = false)
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (e.row.status == "open") BrassButton("Settle", Modifier.weight(1f), tone = p.verdigris, onClick = onSettle)
        BrassButton("Strike out", Modifier.weight(1f), tone = p.inkFaint, onClick = onDelete)
    }
    if (e.live) LedgerLine("Zerodha orders", e.orders.joinToString { it.takeLast(6) })
    e.row.trade?.let { tr ->
        LedgerLine("Settled at", num(tr.settlement))
        LedgerLine("Cost", rs(tr.cost))
    }
}
