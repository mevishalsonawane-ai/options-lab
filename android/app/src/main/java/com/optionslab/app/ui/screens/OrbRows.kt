package com.optionslab.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import com.optionslab.app.ui.components.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.optionslab.app.data.OrbArms
import com.optionslab.app.ui.AppModel
import com.optionslab.app.ui.components.BrassButton
import com.optionslab.app.ui.components.Note
import com.optionslab.app.ui.components.Rule
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import kotlinx.coroutines.delay
import java.util.Locale

private fun rs(x: Double) = (if (x < 0) "−₹" else "₹") + String.format(Locale.ENGLISH, "%,.0f", kotlin.math.abs(x))
private fun px(x: Double) = String.format(Locale.ENGLISH, "%.2f", x)

/**
 * The two built-in ORB arms on Home's Strategies card: an arm switch each, what
 * the arm is doing now, a waiting approval, and a tap for the day's detail and the
 * forward test. New entries follow the Paper/Live switch; in Live each one is
 * approved with the PIN. An open position shows the account it is in.
 */
@Composable
fun OrbRows(model: AppModel) {
    val p = LocalPalette.current
    val v by model.orb.collectAsState()
    var choosing by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf(false) }
    var reauthFor by remember { mutableStateOf<String?>(null) }
    val settings by model.settings.collectAsState()
    val live = settings.live && settings.allowRealOrders
    // Home's own poll (only while the app is on screen) refreshes the arm states every 20 s.
    val view = v ?: return

    view.arms.forEachIndexed { i, a ->
        if (i > 0) Rule()
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable { detail = true }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(a.arm.label, style = Type.body.copy(color = p.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
                    Spacer(Modifier.width(8.dp))
                    val (label, color) = when {
                        a.open != null -> if (a.open.live) "IN TRADE · LIVE" to p.oxblood else "IN TRADE · PAPER" to p.verdigris
                        a.armed && live -> "ARMED · LIVE · APPROVE WITH PIN" to p.oxblood
                        a.armed -> "ARMED · PAPER · ${if (a.automatic) "AUTO" else "APPROVE"}" to p.verdigris
                        else -> "OFF" to p.inkFaint
                    }
                    Text(label, style = Type.label.copy(color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold),
                        modifier = Modifier.background(color.copy(alpha = 0.12f), RoundedCornerShape(50)).padding(horizontal = 7.dp, vertical = 2.dp))
                }
                val line = a.open?.let { o ->
                    val m = a.mark
                    "${o.right} ${o.symbol.takeLast(7).dropLast(2)} · in ${px(o.entry)}" + (m?.let { " · now ${px(it)} · ${rs((it - o.entry) * o.qty)}" } ?: "") +
                        (o.stopTrigger?.let { " · stop ${px(it)}" } ?: "")
                } ?: when {
                    !a.armed -> "BANKNIFTY opening-range break" + if (a.arm.freshOnly) ", fresh breaks only" else ""
                    else -> OrbArms.describe(a.status) + (view.range?.let { r -> " Range ${px(r.second)}–${px(r.first)}." } ?: "")
                }
                Text(line, style = Type.bodySmall.copy(color = p.inkSoft, fontSize = 12.sp), maxLines = 2)
                val closed = a.today.filter { !it.open }
                if (closed.isNotEmpty()) Text("Today: ${closed.size} closed · ${rs(closed.sumOf { (it.grossPnl ?: 0.0) - it.charges })} after charges",
                    style = Type.bodySmall.copy(color = p.inkSoft, fontSize = 12.sp))
            }
            Switch(
                checked = a.armed,
                onCheckedChange = { on -> if (on) choosing = a.arm.source else model.armOrb(a.arm.source, false, a.automatic) },
                colors = SwitchDefaults.colors(checkedTrackColor = p.verdigris, checkedThumbColor = p.card),
            )
        }
        a.pending?.let { pd ->
            Column(Modifier.fillMaxWidth().padding(bottom = 10.dp).background(p.amber.copy(alpha = 0.12f), RoundedCornerShape(12.dp)).padding(12.dp)) {
                Text("Breakout on the %02d:%02d bar: BUY ${pd.right}, 1 lot, ${if (live) "LIVE on Zerodha" else "paper"}. Lapses at %02d:%02d."
                    .format(pd.signalBar.hour, pd.signalBar.minute, pd.expires.hour, pd.expires.minute),
                    style = Type.bodySmall.copy(color = p.ink, fontWeight = FontWeight.SemiBold))
                Row(Modifier.padding(top = 8.dp)) {
                    BrassButton(if (live) "Approve with PIN" else "Approve entry", Modifier.weight(1f), tone = if (live) p.oxblood else p.verdigris) {
                        if (live) reauthFor = a.arm.source else model.approveOrb(a.arm.source)
                    }
                    Spacer(Modifier.width(8.dp))
                    BrassButton("Skip", tone = p.inkSoft) { model.skipOrb(a.arm.source) }
                }
            }
        }
    }
    val f = view.forward
    Text("Forward test: ${f.trades}/60 trades · ${f.days}/40 days · net ${rs(f.net)}" + (f.t?.let { " · t %.2f".format(Locale.ENGLISH, it) } ?: ""),
        style = Type.bodySmall.copy(color = p.inkSoft, fontSize = 12.sp), modifier = Modifier.padding(bottom = 6.dp).clickable { detail = true })

    choosing?.let { src ->
        val label = view.arms.first { it.arm.source == src }.arm.label
        AlertDialog(
            onDismissRequest = { choosing = null },
            properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
            title = { Text("Arm $label" + if (live) " (LIVE)" else " (paper)", style = Type.title) },
            text = {
                Column {
                    Text(if (live) "The app is in LIVE: each entry goes to Zerodha (1 lot MIS) only after you approve it with your PIN. " +
                        "Switch to Paper and new entries go to the paper account. An open position always exits in the account it entered."
                        else "The app is in Paper: entries go to the paper account. If you switch to Live, new entries go to Zerodha and each needs your PIN. How should paper entries go out?",
                        style = Type.bodySmall.copy(color = p.inkSoft))
                    OrbChoice("Automatic", "The paper entry is placed on the breakout bar without asking.") { model.armOrb(src, true, true); choosing = null }
                    OrbChoice("Ask me to approve", "You get a notification on a breakout; the entry goes only if you approve before the next bar closes.") {
                        model.armOrb(src, true, false); choosing = null
                    }
                    Note("Either way the −40 stop rests as an order (paper book, or an SL order at Zerodha), and the +40 target and the 15:10 square-off run by themselves.", Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = {},
            dismissButton = { TextButton({ choosing = null }) { Text("Cancel") } },
        )
    }
    reauthFor?.let { src -> Reauth(model, onOk = { reauthFor = null; model.approveOrb(src, pinConfirmed = true) }, onCancel = { reauthFor = null }) }
    if (detail) OrbDetail(view) { detail = false }
}

@Composable
private fun OrbChoice(title: String, detail: String, onClick: () -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(top = 10.dp).background(p.chip, RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(12.dp)) {
        Text(title, style = Type.body.copy(color = p.ink, fontWeight = FontWeight.SemiBold))
        Text(detail, style = Type.bodySmall.copy(color = p.inkSoft, fontSize = 12.sp))
    }
}

/** The day in full: strike, contracts, range, every trade of both arms, the evening replay and the pass rule. */
@Composable
private fun OrbDetail(v: OrbArms.View, onClose: () -> Unit) {
    val p = LocalPalette.current
    AlertDialog(
        onDismissRequest = onClose,
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text("ORB arms · today", style = Type.title) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                val small = Type.bodySmall.copy(color = p.ink, fontSize = 12.sp)
                val soft = Type.bodySmall.copy(color = p.inkSoft, fontSize = 12.sp)
                val head = Type.body.copy(color = p.ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                v.legs?.let { l -> Text("Strike ${l.strike} (09:20 bar) · expiry ${l.expiry} · lot ${l.ce.lotSize}", style = small) }
                    ?: Text("No strike fixed yet today (set from the 09:20 bar once an arm runs).", style = soft)
                v.range?.let { r -> Text("Opening range ${px(r.second)} – ${px(r.first)}", style = small) }
                for (a in v.arms) {
                    Text(a.arm.label, style = head, modifier = Modifier.padding(top = 12.dp))
                    Text(OrbArms.describe(a.status), style = soft)
                    if (a.today.isEmpty()) Text("No trades today.", style = soft)
                    a.today.forEach { t ->
                        val tail = if (t.open) "open" else "${px(t.exit ?: 0.0)} ${t.why?.replace('_', ' ')} · ${rs((t.grossPnl ?: 0.0) - t.charges)}"
                        Text("%02d:%02d ${if (t.live) "LIVE" else "paper"} BUY ${t.right} @ ${px(t.entry)} → $tail".format(t.entryTime.hour, t.entryTime.minute), style = small)
                    }
                }
                v.replay?.let { r ->
                    Text("Evening replay · ${v.replayDay}", style = head, modifier = Modifier.padding(top = 14.dp))
                    Text("Index closed ${if (r.optBoolean("up")) "up" else "down"}. Decide on a bar's close, fill on the next bar's open; no orders.", style = soft)
                    r.optString("note").takeIf { it.isNotEmpty() }?.let { Text(it, style = soft) }
                    for (a in v.arms) {
                        val arr = r.optJSONArray(a.arm.source) ?: continue
                        val total = (0 until arr.length()).sumOf { arr.getJSONObject(it).getDouble("pnl") }
                        Text("${a.arm.label}: ${arr.length()} trade(s), ${rs(total)} before charges", style = small)
                        for (i in 0 until arr.length()) arr.getJSONObject(i).let { t ->
                            Text("  ${t.getString("bar")} ${t.getString("right")} ${px(t.getDouble("entry"))} → ${px(t.getDouble("exit"))} ${t.getString("why").replace('_', ' ')}", style = soft)
                        }
                    }
                }
                val f = v.forward
                Text("Forward test (pre-registered)", style = head, modifier = Modifier.padding(top = 14.dp))
                Text("${f.trades} of 60 trades · ${f.days} of 40 days · net ${rs(f.net)} after charges" + if (f.finished) " · FINISHED" else "", style = small)
                f.checks.forEach { (name, ok) -> Text("${if (ok) "✓" else "✗"} $name", style = small.copy(color = if (ok) p.verdigris else p.oxblood)) }
                Note("A pass earns a longer forward test at the same size, not real money. Trades you close with Stop for today are left out.",
                    Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(onClose) { Text("Close") } },
    )
}
