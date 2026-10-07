package com.optionslab.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import com.optionslab.app.ui.components.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import com.optionslab.app.ui.components.TextButton
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
 * The built-in arms on Home's Strategies card: ORB, ORB Fresh, ORB Sweep, Range Fade (un-retired by Boss on 07 Oct, on
 * paper), the Hero arm and Liquidity 15+5, each with its switch, what the arm is doing now, a waiting approval, and a tap
 * for the day's detail (with the four's 2021–2026 record, as information). New entries follow the Paper/Live switch; in
 * Live arming takes the PIN and each entry not cleared for Zerodha is approved with it. An open position shows the account
 * it is in. Under the rows, the research shadows (no orders) and the tap for their records.
 */
@Composable
fun OrbRows(model: AppModel) {
    val v by model.orb.collectAsState(kotlinx.coroutines.Dispatchers.Main.immediate)
    val settings by model.settings.collectAsState(kotlinx.coroutines.Dispatchers.Main.immediate)
    // Home's own poll (only while the app is on screen) refreshes the arm states every 20 s.
    val view = v ?: return
    OrbRowsContent(view, live = settings.live && settings.allowRealOrders,
        actions = object : OrbActions {
            override fun arm(source: String, on: Boolean, automatic: Boolean, pinConfirmed: Boolean) { model.armOrb(source, on, automatic, pinConfirmed) }
            override fun approve(source: String, pinConfirmed: Boolean) { model.approveOrb(source, pinConfirmed) }
            override fun skip(source: String) { model.skipOrb(source) }
            override fun shadowOff(id: String) { model.shadowOff(id) }
            override fun lots(n: Int) { model.liquidityLots(n) }
        },
        reauth = { why, onOk, onCancel -> if (why == null) Reauth(model, onOk = onOk, onCancel = onCancel) else Reauth(model, onOk = onOk, onCancel = onCancel, why = why) },
        paperRecord = { com.optionslab.app.data.ForwardRecords.liquidityEquity() },
        openRead = { p -> OrbArms.liquidityOpenNow(p.arm, p.symbol) })
}

/** What the ORB rows ask the model to do (an interface so tests can record it without an [AppModel]). */
internal interface OrbActions {
    fun arm(source: String, on: Boolean, automatic: Boolean, pinConfirmed: Boolean)
    fun approve(source: String, pinConfirmed: Boolean)
    fun skip(source: String)
    /** A shadow re-armed on paper on Boss's yes ([OrbArms.View.shadows]) switched off again. */
    fun shadowOff(id: String) {}
    /** Liquidity 15+5's size for its new entries ([OrbArms.setLiquidityLots]): a raise only after Boss's yes in the dialog. */
    fun lots(n: Int) {}
}

/** The ORB rows from the arms' [view] and callbacks; [reauth] is the PIN prompt ([Reauth] in the app), with its reason or the default. */
@Composable
internal fun OrbRowsContent(
    view: OrbArms.View, live: Boolean, actions: OrbActions,
    reauth: @Composable (why: String?, onOk: () -> Unit, onCancel: () -> Unit) -> Unit,
    /** A closed Liquidity 15+5 paper trade's replay (the detail's tap on it; [LiquidityReplayData] in the app). Reads only. */
    replay: suspend (OrbArms.Position) -> com.optionslab.ira.LiquidityReplay.Replay? = { LiquidityReplayData.load(it) },
    /**
     * Liquidity 15+5's paper record under its row ([LiquidityEquityCard]; [com.optionslab.app.data.ForwardRecords.liquidityEquity]
     * in the app), read off the main thread. Null: no card. Reads only.
     */
    paperRecord: (suspend () -> com.optionslab.ira.LiquidityEquity.Equity?)? = null,
    /**
     * Liquidity 15+5's open position as its live panel reads it ([LiquidityOpenPanel]; [OrbArms.liquidityOpenNow] in the
     * app), off the main thread on each refresh of the rows. Null: the panel shows the row's own position and mark. Reads only.
     */
    openRead: (suspend (OrbArms.Position) -> OrbArms.LiquidityOpenNow?)? = null,
) {
    val p = LocalPalette.current
    var choosing by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf(false) }
    var reauthFor by remember { mutableStateOf<String?>(null) }
    // Arming while in Live: (source, automatic), after the PIN or fingerprint.
    var armAuth by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    // Arming the Hero arm (not proven): its own confirmation, with the record, before it is switched on.
    var heroConfirm by remember { mutableStateOf<String?>(null) }
    // Liquidity's size raised: (from, to), asked before it applies (more lots is more risk); lowering applies at once.
    var lotsConfirm by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    view.arms.forEachIndexed { i, a ->
        if (i > 0) Rule()
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable { detail = true }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(a.arm.label, style = Type.body.copy(color = p.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
                    Spacer(Modifier.width(8.dp))
                    // The bot stopped for today: an armed arm never looks normally ARMED (it makes no new entries today).
                    val dayStopped = view.stopped != null && a.armed
                    val (label, color) = when {
                        a.open != null -> if (a.open.live) "IN TRADE · LIVE" to p.oxblood else "IN TRADE · PAPER" to p.verdigris
                        dayStopped -> "STOPPED TODAY · ARMED" to p.oxblood
                        a.armed && a.arm.hero -> "ARMED · PAPER ONLY · NOT PROVEN" to p.amber
                        a.armed && a.arm.paperOnly -> "ARMED · PAPER ONLY · AUTO" to p.verdigris
                        a.armed && live && a.liveOk -> "ARMED · LIVE · ${if (a.automatic) "AUTO" else "APPROVE"}" to p.oxblood
                        a.armed && live -> "ARMED · LIVE · APPROVE (re-arm for auto)" to p.oxblood
                        a.armed -> "ARMED · PAPER · ${if (a.automatic) "AUTO" else "APPROVE"}" to p.verdigris
                        else -> "OFF" to p.inkFaint
                    }
                    Text(label, style = Type.label.copy(color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold),
                        modifier = Modifier.background(color.copy(alpha = 0.12f), RoundedCornerShape(50)).padding(horizontal = 7.dp, vertical = 2.dp))
                }
                val line = a.open?.let { o ->
                    val m = a.mark
                    "${o.right} ${o.symbol.takeLast(7).dropLast(2)} · in ${px(o.entry)}" + (m?.let { " · now ${px(it)} · ${rs((it - o.entry) * o.qty)}" } ?: "") +
                        (o.stopTrigger?.let { " · stop ${px(it)}" } ?: "") +
                        // The Hero arm: the part already sold at 5x.
                        (o.soldAt?.takeIf { o.sold > 0 }?.let { " · ${o.sold} sold @ ${px(it)}" } ?: "") +
                        // The profit lock earned so far (25 / 50 / 75 % of the target reached -> breakeven after charges / +25% / +50%).
                        (com.optionslab.engine.orb.ProfitLock.targetOf(a.arm)?.takeIf { o.ladder }
                            ?.let { tg -> com.optionslab.engine.orb.ProfitLock.level(o.entry, tg, o.peak ?: o.entry,
                                com.optionslab.engine.orb.ProfitLock.roundTripPerUnit(o.entry, o.qty)) }
                            ?.let { " · locked ${px(it)}" } ?: "")
                } ?: when {
                    view.stopped != null && a.armed -> view.stopped.orEmpty()
                    !a.armed && a.arm.liquidity -> "BANKNIFTY (15 + 5-min) + FINNIFTY (30 + 5-min) liquidity pool taken on a swing zone · stop −15% · out 30 index pts back (FINNIFTY 15) or not +5% in 20 min · else at the next liquidity"
                    a.arm.liquidity -> a.status
                    !a.armed && a.arm.hero -> keepNumbersWhole("NIFTY expiry days only · 13:30–14:45 straddle +15% and a 0.25% move in 15 min · buys a Rs 1–5 OTM option, Rs 5,000 · ${com.optionslab.engine.orb.HeroRules.EXITS} · ${com.optionslab.engine.orb.HeroRules.NOT_PROVEN}")
                    a.arm.hero -> OrbArms.describe(a.status)
                    !a.armed && a.arm.fade -> "BANKNIFTY touch of the range edge, faded to the middle · paper only · -40 / +40 · profit lock"
                    !a.armed && a.arm.sweep -> "BANKNIFTY failed break of the opening range, faded · paper only · -40 / +80 · profit lock"
                    !a.armed -> "BANKNIFTY opening-range break" + (if (a.arm.freshOnly) ", fresh breaks only" else "") + " · profit lock"
                    else -> OrbArms.describe(a.status) + (view.range?.let { r -> " Range ${px(r.second)}–${px(r.first)}." } ?: "")
                }
                Text(line, style = Type.bodySmall.copy(color = p.inkSoft, fontSize = 12.sp))
                // Liquidity 15+5's size for new entries; an open position keeps its own quantity.
                a.lots?.let { n ->
                    // "Lots: 1 · 2 · 3" (a raise asks first, a cut applies at once).
                    LotsChooser(n) { pick -> if (com.optionslab.engine.orb.LiquidityLots.raises(n, pick)) lotsConfirm = n to pick else if (pick != n || a.lotsAsk != null) actions.lots(pick) }
                    Text(keepNumbersWhole(com.optionslab.engine.orb.LiquidityLots.line(n, a.lotSizes)), style = Type.bodySmall.copy(color = p.inkSoft, fontSize = 12.sp))
                    // A restore's higher size waits for Boss: it never raised the size by itself.
                    a.lotsAsk?.let { w -> Text(keepNumbersWhole("The backup had ${com.optionslab.engine.orb.LiquidityLots.words(w)}: it trades " +
                        "${com.optionslab.engine.orb.LiquidityLots.words(n)} until you choose $w."), style = Type.bodySmall.copy(color = p.amber, fontSize = 12.sp)) }
                }
                // Liquidity 15+5's pre-registered candidates, tracked in its shadow per lot (they never change what it trades).
                a.shadow?.let { s -> Text(keepNumbersWhole(com.optionslab.engine.orb.LiquidityShadow.line(s)), style = Type.bodySmall.copy(color = p.inkSoft, fontSize = 12.sp)) }
                val closed = a.today.filter { !it.open }
                if (closed.isNotEmpty()) Text("Today: ${closed.size} closed · ${rs(closed.sumOf { (it.grossPnl ?: 0.0) - it.charges })} after charges",
                    style = Type.bodySmall.copy(color = p.inkSoft, fontSize = 12.sp))
            }
            Switch(
                modifier = Modifier.semantics { contentDescription = "Arm ${a.arm.label}" },
                checked = a.armed,
                // The Hero arm takes its own confirmation (not proven); Liquidity 15+5 chooses automatic or approve.
                onCheckedChange = { on -> if (on && a.arm.hero) heroConfirm = a.arm.source
                    else if (on && a.arm.paperOnly) actions.arm(a.arm.source, true, true, false)
                    else if (on) choosing = a.arm.source else actions.arm(a.arm.source, false, a.automatic, false) },
                colors = SwitchDefaults.colors(checkedTrackColor = p.verdigris, checkedThumbColor = p.card),
            )
        }
        a.pending?.let { pd ->
            Column(Modifier.fillMaxWidth().padding(bottom = 10.dp).background(p.amber.copy(alpha = 0.12f), RoundedCornerShape(12.dp)).padding(12.dp)) {
                Text("Breakout on the %02d:%02d bar: BUY ${pd.right}, ${a.lots?.let { com.optionslab.engine.orb.LiquidityLots.words(it) } ?: "1 lot"}, ${if (live) "LIVE on Zerodha" else "paper"}. Lapses at %02d:%02d."
                    .format(pd.signalBar.hour, pd.signalBar.minute, pd.expires.hour, pd.expires.minute),
                    style = Type.bodySmall.copy(color = p.ink, fontWeight = FontWeight.SemiBold))
                Row(Modifier.padding(top = 8.dp)) {
                    BrassButton(if (live) "Approve with PIN" else "Approve entry", Modifier.weight(1f), tone = if (live) p.oxblood else p.verdigris) {
                        if (live) reauthFor = a.arm.source else actions.approve(a.arm.source, false)
                    }
                    Spacer(Modifier.width(8.dp))
                    BrassButton("Skip", tone = p.inkSoft) { actions.skip(a.arm.source) }
                }
            }
        }
        // Liquidity 15+5's open position, live: its exits as they stand and how far each is (reads only; never in the GOLD build).
        if (a.arm.liquidity && a.open != null && !com.optionslab.app.BuildConfig.GOLD) LiquidityOpenPanel(a.open, a.mark, openRead)
        // Liquidity 15+5's paper record since its forward test began, against its backtest; read again when a trade closes.
        // Reads only, never in the GOLD build.
        if (a.arm.liquidity && paperRecord != null && !com.optionslab.app.BuildConfig.GOLD)
            LiquidityEquityCard(a.today.count { !it.open && !it.live }, paperRecord)
    }
    // The research shadows (no orders): for each arm that has them, one line - the only one, or the best of its two or three
    // (the tap lists them all) - then the new candidate. They never change what an arm trades.
    if (view.shadows.isNotEmpty()) {
        Rule()
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).clickable { detail = true }) {
            Text("Shadows · no orders", style = Type.label.copy(color = p.inkFaint, fontSize = 11.sp, fontWeight = FontWeight.Bold))
            view.arms.forEach { a ->
                armShadowLine(view.shadows, a.arm.source)?.let { line ->
                    Text("${a.arm.label}: ${keepNumbersWhole(line)}", style = Type.bodySmall.copy(color = p.inkSoft, fontSize = 12.sp),
                        modifier = Modifier.padding(top = 2.dp))
                }
            }
            // The new candidate (no arm of its own): its own line and its shadow.
            view.shadows.filter { it.variant.arms.isEmpty() }.forEach { s ->
                Text("${s.variant.label}: new candidate, in the shadow only", style = Type.bodySmall.copy(color = p.inkSoft, fontSize = 12.sp),
                    modifier = Modifier.padding(top = 2.dp))
                Text(keepNumbersWhole(s.line), style = Type.bodySmall.copy(color = p.inkSoft, fontSize = 12.sp), modifier = Modifier.padding(start = 10.dp))
            }
            Text("Tap for every shadow's record and why its losers lost", style = Type.bodySmall.copy(color = p.inkFaint, fontSize = 12.sp),
                modifier = Modifier.padding(top = 2.dp))
        }
    }

    choosing?.let { src ->
        val label = view.arms.first { it.arm.source == src }.arm.label
        AlertDialog(
            onDismissRequest = { choosing = null },
            properties = DialogProperties(securePolicy = com.optionslab.app.security.Capture.policy),
            title = { Text("Arm $label" + if (live) " (LIVE)" else " (paper)", style = Type.title) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(if (live) "The app is in LIVE: entries go to Zerodha, ${view.arms.first { it.arm.source == src }.lots?.let { com.optionslab.engine.orb.LiquidityLots.words(it) } ?: "1 lot"} MIS. Arming takes your PIN or fingerprint once. " +
                        "An open position always exits in the account it entered."
                        else "The app is in Paper: entries go to the paper account. To trade automatically on Zerodha, switch to Live and arm it again (PIN once).",
                        style = Type.bodySmall.copy(color = p.inkSoft))
                    OrbChoice("Automatic", "Buys on the breakout and sells on the stop, target or 15:10 by itself, every trading day, until you switch it off.") {
                        if (live) armAuth = src to true else actions.arm(src, true, true, false); choosing = null
                    }
                    OrbChoice("Ask me to approve", "You get a notification on a breakout; the entry goes only if you approve before the next bar closes.") {
                        if (live) armAuth = src to false else actions.arm(src, true, false, false); choosing = null
                    }
                    Note(if (view.arms.first { it.arm.source == src }.arm.liquidity)
                        "Either way the stop 15% below the price paid rests as an order (paper book, or an SL order at Zerodha), and the exits at the next liquidity, on a failed break, on new liquidity and at 15:10 run by themselves."
                        else "Either way the −40 stop rests as an order (paper book, or an SL order at Zerodha), and the +40 target, the profit lock (a quarter of the way up the stop moves to the price paid, half way to +10, three quarters to +20) and the 15:10 square-off run by themselves.", Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = {},
            dismissButton = { TextButton({ choosing = null }) { Text("Cancel") } },
        )
    }
    // The ORB arms keep their own words; Liquidity 15+5 names itself (and says when each entry still waits for approval).
    armAuth?.let { (src, auto) -> reauth(if (view.arms.firstOrNull { it.arm.source == src }?.arm?.liquidity == true)
            "Enter your app PIN to arm Liquidity 15+5 on Zerodha. " +
                (if (auto) "It then trades real money by itself until you switch it off." else "Each entry still waits for your approval with the PIN.")
        else "Enter your app PIN to arm ORB on Zerodha. It then trades real money by itself until you switch it off.",
        { armAuth = null; actions.arm(src, true, auto, true) }, { armAuth = null }) }
    reauthFor?.let { src -> reauth(null, { reauthFor = null; actions.approve(src, true) }, { reauthFor = null }) }
    heroConfirm?.let { src ->
        AlertDialog(
            onDismissRequest = { heroConfirm = null },
            properties = DialogProperties(securePolicy = com.optionslab.app.security.Capture.policy),
            title = { Text("Arm Hero (expiry) (paper)", style = Type.title) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(com.optionslab.engine.orb.HeroRules.NOT_PROVEN + ". It never trades on Zerodha.",
                        style = Type.body.copy(color = p.oxblood, fontWeight = FontWeight.SemiBold))
                    Text("On NIFTY expiry days only, from 13:30 to 14:45: when the ATM straddle is 15% above its low since 12:00 and " +
                        "NIFTY has moved 0.25% in 15 minutes, it buys the nearest OTM option on that side priced Rs 1–5 with a LIMIT " +
                        "order, up to Rs 5,000 of premium, once a day. It sells half at 5× the price paid and the rest at 20× or 15:05, " +
                        "with a stop at −60% of the premium on a minute's close.", style = Type.bodySmall.copy(color = p.inkSoft))
                    Note("The study, with the old exits (all out at 15:05): +Rs 4.4 lakh in 2023–24, but three trades made all of it; " +
                        "2025–26 out of sample lost on all 13 trades (−Rs 64,190). The new exits are not proven either. Expect about " +
                        "Rs 5,000 lost on most firing days. It disarms itself after 12 losing expiry " +
                        "days in a row or Rs 50,000 lost.", Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = { TextButton({ heroConfirm = null; actions.arm(src, true, true, false) }) { Text("Arm on paper") } },
            dismissButton = { TextButton({ heroConfirm = null }) { Text("Cancel") } },
        )
    }
    lotsConfirm?.let { (from, to) ->
        AlertDialog(
            onDismissRequest = { lotsConfirm = null },
            properties = DialogProperties(securePolicy = com.optionslab.app.security.Capture.policy),
            title = { Text("Liquidity 15+5: ${com.optionslab.engine.orb.LiquidityLots.words(to)}?", style = Type.title) },
            text = {
                Text(keepNumbersWhole("Each new entry will buy ${com.optionslab.engine.orb.LiquidityLots.words(to)} of its contract's lot instead of " +
                    "${com.optionslab.engine.orb.LiquidityLots.words(from)} - ${to}x the rupees won or lost a trade, the 15% stop and every exit on all of it. " +
                    "An open position keeps its own quantity." + if (live) " On Zerodha the Bot settings' max lots and daily loss still apply." else ""),
                    style = Type.bodySmall.copy(color = p.inkSoft))
            },
            confirmButton = { TextButton({ lotsConfirm = null; actions.lots(to) }) { Text("Trade ${com.optionslab.engine.orb.LiquidityLots.words(to)}") } },
            dismissButton = { TextButton({ lotsConfirm = null }) { Text("Cancel") } },
        )
    }
    if (detail) OrbDetail(view, onShadowOff = { actions.shadowOff(it) }, replay = replay) { detail = false }
}

/** "Lots: 1 · 2 · 3": the size chosen in bold; each figure a 48 dp target. */
@Composable
private fun LotsChooser(lots: Int, onPick: (Int) -> Unit) {
    val p = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Lots:", style = Type.bodySmall.copy(color = p.inkSoft, fontSize = 12.sp))
        com.optionslab.engine.orb.LiquidityLots.CHOICES.forEachIndexed { i, n ->
            if (i > 0) Text("·", style = Type.bodySmall.copy(color = p.inkFaint))
            val on = n == lots
            androidx.compose.foundation.layout.Box(
                Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .semantics { contentDescription = "Liquidity $n lot${if (n == 1) "" else "s"}" + if (on) ", chosen" else "" }
                    .clickable { onPick(n) },
                contentAlignment = Alignment.Center,
            ) {
                Text(keepNumbersWhole("$n"), style = Type.body.copy(color = if (on) p.verdigris else p.inkSoft, fontSize = 15.sp,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal),
                    modifier = if (on) Modifier.background(p.verdigris.copy(alpha = 0.12f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp)
                        else Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
            }
        }
    }
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
private fun OrbDetail(v: OrbArms.View, onShadowOff: (String) -> Unit = {},
                      replay: suspend (OrbArms.Position) -> com.optionslab.ira.LiquidityReplay.Replay? = { null }, onClose: () -> Unit) {
    val p = LocalPalette.current
    // A closed Liquidity 15+5 paper trade tapped: its replay over the detail (never in the GOLD build).
    var replaying by remember { mutableStateOf<OrbArms.Position?>(null) }
    AlertDialog(
        onDismissRequest = onClose,
        properties = DialogProperties(securePolicy = com.optionslab.app.security.Capture.policy),
        title = { Text("Arms · today", style = Type.title) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                val small = Type.bodySmall.copy(color = p.ink, fontSize = 12.sp)
                val soft = Type.bodySmall.copy(color = p.inkSoft, fontSize = 12.sp)
                val head = Type.body.copy(color = p.ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                v.legs?.let { l -> Text("Strike ${l.strike} (09:20 bar) · expiry ${l.expiry} · lot ${l.ce.lotSize}", style = small) }
                    ?: Text("No strike fixed yet today (set from the 09:20 bar once an arm runs).", style = soft)
                v.range?.let { r -> Text("Opening range ${px(r.second)} – ${px(r.first)}", style = small) }
                v.stopped?.let { s -> Text(s, style = small.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(top = 8.dp)) }
                for (a in v.arms) {
                    Text(a.arm.label, style = head, modifier = Modifier.padding(top = 12.dp))
                    Text(OrbArms.describe(a.status), style = soft)
                    // ORB, ORB Fresh, ORB Sweep, Range Fade: their 2021–2026 record, as information (never a block).
                    a.record?.let { r -> Text(keepNumbersWhole(recordLine(r)), style = soft) }
                    if (a.today.isEmpty()) Text("No trades today.", style = soft)
                    // Liquidity 15+5: a closed paper trade opens its replay (the chart around it, the result and its lesson).
                    if (a.today.any { replayable(a, it) }) Text("Tap a closed trade to replay it.", style = soft)
                    a.today.forEach { t ->
                        val tail = if (t.open) "open" else "${px(t.exit ?: 0.0)} ${t.why?.replace('_', ' ')} · ${rs((t.grossPnl ?: 0.0) - t.charges)}"
                        // Liquidity 15+5's quantity said (its size can be 1-3 lots); the rupees are the trade's own at that quantity.
                        val qty = if (a.arm.liquidity) " ×${t.qty}" else ""
                        val line = "%02d:%02d ${if (t.live) "LIVE" else "paper"} BUY ${t.right}$qty @ ${px(t.entry)} → $tail".format(t.entryTime.hour, t.entryTime.minute)
                        if (replayable(a, t)) Text(line, style = small.copy(color = p.verdigris),
                            modifier = Modifier.heightIn(min = 48.dp).clickable(onClickLabel = "Replay this trade") { replaying = t }.padding(vertical = 4.dp))
                        else Text(line, style = small)
                        // The Hero arm: the half sold at 5x, and the book (bid / ask / quantities, or the last price) at the signal and each exit.
                        t.soldAt?.takeIf { t.sold > 0 }?.let { s ->
                            Text(keepNumbersWhole("  ${t.sold} sold @ ${px(s)} at 5×" + (t.soldTime?.let { " (%02d:%02d)".format(it.hour, it.minute) } ?: "") +
                                ", ${t.qty} held to the end"), style = soft)
                        }
                        t.seen.forEach { Text(keepNumbersWhole("  " + com.optionslab.engine.orb.HeroRules.seenLine(it)), style = soft) }
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
                // The shadows (no orders): each variant's record and its trades, newest first.
                if (v.shadows.isNotEmpty()) {
                    Text("Shadows · no orders", style = head, modifier = Modifier.padding(top = 14.dp))
                    Text("What each rule would have done at live prices, paper fills and real charges. Nothing is ordered unless you " +
                        "say yes to re-arming one on paper.", style = soft)
                    for (s in v.shadows) {
                        Text("${s.variant.label} · ${s.variant.name}", style = small.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(top = 8.dp))
                        Text(keepNumbersWhole(s.line), style = small)
                        Text(s.variant.description, style = soft)
                        // Why its losers lost (the losing-trades study's groups).
                        com.optionslab.engine.orb.ShadowStudy.reasonLine(s.losses)?.let { Text(keepNumbersWhole("Its losers: $it"), style = soft) }
                        if (s.trades.isEmpty()) Text("No shadow trades yet.", style = soft)
                        s.trades.asReversed().take(40).forEach { t ->
                            val tail = if (t.open) "open" else "${px(t.exit ?: 0.0)} ${t.why?.replace('_', ' ')} · ${rs(t.net ?: 0.0)}"
                            val hm = "%02d:%02d".format(t.entryTime.hour, t.entryTime.minute)
                            Text(keepNumbersWhole("${t.entryTime.toLocalDate()} $hm ${if (t.paper) "paper" else "shadow"} ${t.right} ${t.strike} @ ${px(t.entry)} → $tail"),
                                style = soft)
                        }
                        if (s.armed) TextButton({ onShadowOff(s.variant.id) }) { Text("Switch ${s.variant.name} off (back to the shadow)") }
                    }
                }
                val f = v.forward
                Text("ORB forward test (pre-registered)", style = head, modifier = Modifier.padding(top = 14.dp))
                Text("${f.trades} of 60 trades · ${f.days} of 40 days · net ${rs(f.net)} after charges" + if (f.finished) " · FINISHED" else "", style = small)
                f.checks.forEach { (name, ok) -> Text("${if (ok) "✓" else "✗"} $name", style = small.copy(color = if (ok) p.verdigris else p.oxblood)) }
                Note("A pass earns a longer forward test at the same size, not real money. Trades you close with Stop for today are left out.",
                    Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(onClose) { Text("Close") } },
    )
    replaying?.let { t -> LiquidityReplayDialog(t, replay) { replaying = null } }
}

/** A trade the detail can replay: a closed Liquidity 15+5 paper trade (never in the GOLD build). */
private fun replayable(a: OrbArms.ArmView, t: OrbArms.Position): Boolean =
    a.arm.liquidity && !t.open && !t.live && !com.optionslab.app.BuildConfig.GOLD

/**
 * The record line in the day's detail for ORB, ORB Fresh, ORB Sweep or Range Fade: Boss un-retired it on 07 Oct 2026,
 * and what it lost on the 2021–2026 real data - information, never a block.
 */
internal fun recordLine(r: com.optionslab.engine.orb.RetiredArms.Retired): String =
    "You un-retired it on 07 Oct 2026. Its record: ${com.optionslab.engine.orb.RetiredArms.line(r)}."

/**
 * An arm's line in the Shadows section: its one shadow's line, or with two or three the best one's as
 * "Best of N shadows · ..." - the same best as Live vs backtest's ([com.optionslab.app.data.ForwardRecords.bestShadowIndex]:
 * against its own research, not the raw net); null with none.
 */
internal fun armShadowLine(shadows: List<com.optionslab.app.data.ShadowArms.Row>, source: String): String? {
    val mine = shadows.filter { s -> s.variant.arms.any { it.source == source } }
    val best = mine.getOrNull(com.optionslab.app.data.ForwardRecords.bestShadowIndex(mine)) ?: return null
    return com.optionslab.engine.orb.ShadowRules.bestOf(mine.size, best.line)
}

/** Money and counts never break across lines (word joiners inside each figure, e.g. "(+₹1,158"). */
private val FIGURE = Regex("""[(]?[+−-]?₹?\d[\d,.]*""")
internal fun keepNumbersWhole(s: String): String = FIGURE.replace(s) { m -> m.value.toList().joinToString("\u2060") }
