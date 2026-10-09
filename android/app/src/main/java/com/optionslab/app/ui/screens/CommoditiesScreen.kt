package com.optionslab.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.input.pointer.pointerInput
import com.optionslab.app.ui.components.clearOfBars
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.optionslab.app.data.McxMarket
import com.optionslab.app.ui.AppModel
import com.optionslab.app.ui.Load
import com.optionslab.app.ui.Page
import com.optionslab.app.ui.components.BrassButton
import com.optionslab.app.ui.components.FullSpinner
import com.optionslab.app.ui.components.LedgerCard
import com.optionslab.app.ui.components.Note
import com.optionslab.app.ui.components.Token
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import com.optionslab.engine.Kite
import com.optionslab.engine.mcx.Mcx
import com.optionslab.engine.mcx.McxCosts
import com.optionslab.engine.mcx.McxMargin
import com.optionslab.engine.mcx.McxSession
import java.util.Locale

/**
 * Commodities (9 Oct 2026, Boss: "all MCX in the application"; the Options tab's "Commodities" tool): every MCX commodity's
 * near and next future with its price and today's change - crude, natural gas, gold, silver, copper, zinc, aluminium,
 * lead, nickel and their minis - MCX's hours today, and for each future its margin per lot and round-trip charges. A tap
 * opens the future: Buy / Sell (paper here, or the usual Zerodha review in Live), its chart, and the option chain where
 * MCX lists one. Reading only until an order is placed. The three MCX paper bots (9 Oct) are on Home → Strategies
 * ([McxArmRows]); this page says so in one line.
 */
@Composable
fun CommoditiesScreen(model: AppModel, onChart: (String, String) -> Unit, onChain: (String) -> Unit) {
    val s by model.settings.collectAsState(kotlinx.coroutines.Dispatchers.Main.immediate)
    val state by model.commodities.collectAsState(kotlinx.coroutines.Dispatchers.Main.immediate)
    LaunchedEffect(s.live) { model.loadCommodities() }
    // Prices move all evening: re-read every 30 s while the page is in front.
    com.optionslab.app.ui.PollWhileStarted(s.live) { while (true) { kotlinx.coroutines.delay(30_000); model.loadCommodities(quiet = true) } }
    var picked by remember { mutableStateOf<McxMarket.Quote?>(null) }
    CommoditiesContent(state, sessionLine(), onRefresh = { model.loadCommodities() }, onPick = { picked = it },
        futureExitDays = s.mcxFutureExitDays)
    picked?.let { q ->
        McxFutureSheet(q, s.live, futureExitDays = s.mcxFutureExitDays,
            onOrder = { buy, lots, limit, product ->
                val side = if (buy) Kite.Side.BUY else Kite.Side.SELL
                if (s.live) model.planMcx(q.contract, side, lots, product, limit)
                else model.paperPlaceMcx(q.contract, side.name, lots, if (limit != null) "LIMIT" else "MARKET", product, limit)
            },
            onChart = { picked = null; onChart(q.contract.tradingSymbol, Mcx.EXCHANGE) },
            onChain = if (q.contract.name in MCX_CHAINS) ({ picked = null; onChain(q.contract.name) }) else null,
            onClose = { picked = null })
    }
}

/** "MCX open today 09:00-23:30" / "MCX shut today" (MCX's own calendar). */
internal fun sessionLine(): String {
    val w = runCatching { McxMarket.todayWindow() }.getOrNull()
    val open = runCatching { McxMarket.isOpen() }.getOrDefault(false)
    return when {
        w == null -> "MCX does not trade today."
        open -> "MCX is open now · today ${McxSession.say(w)}"
        else -> "MCX is closed now · today ${McxSession.say(w)}"
    }
}

/** The page from plain values (tests drive it without an [AppModel] or the network). */
@Composable
internal fun CommoditiesContent(state: Load<List<McxMarket.Quote>>, session: String, onRefresh: () -> Unit, onPick: (McxMarket.Quote) -> Unit,
                                futureExitDays: Int = com.optionslab.engine.mcx.McxExpiry.DEFAULT_FUTURE_EXIT_DAYS) {
    val p = LocalPalette.current
    Page {
        item { PageTitle("Commodities", "MCX futures: near and next month · prices, margin and charges per lot") }
        item {
            LedgerCard {
                Text(session, style = Type.body.copy(color = p.ink, fontWeight = FontWeight.SemiBold))
                Note("Hours 09:00-23:30, to 23:55 from 2 Nov (US winter time). MCX options are closed by 23:00 the day before expiry; " +
                    "gold, silver and metal futures $futureExitDays trading days before expiry (Settings → Bot settings).")
                BrassButton("Refresh prices", Modifier.padding(top = 6.dp), tone = p.inkSoft, onClick = onRefresh)
            }
        }
        // The MCX paper bots moved to Home → Strategies (9 Oct, Boss); one line here says where, so their switches live in one place.
        item(key = "mcx-paper-arms") { LedgerCard { Note(MCX_ARMS_WHERE) } }
        when (state) {
            Load.Idle -> item { LedgerCard { FullSpinner("Reading MCX prices") } }
            is Load.Busy -> item { LedgerCard { FullSpinner(state.label) } }
            is Load.Failed -> item { LedgerCard(accent = p.amber) { Note(state.why) } }
            is Load.Done -> {
                val byName = state.value.groupBy { it.contract.name }
                if (byName.isEmpty()) item { LedgerCard { Note("No MCX prices yet. MCX may be shut, or its list could not be read.") } }
                for (name in Mcx.NAMES) {
                    val rows = byName[name] ?: continue
                    item(key = name) {
                        LedgerCard {
                            val c = Mcx.commodity(name)
                            Text(c?.label ?: name, style = Type.title.copy(color = p.ink, fontSize = 15.sp))
                            rows.sortedBy { it.contract.expiry }.forEach { q -> QuoteRow(q) { onPick(q) } }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuoteRow(q: McxMarket.Quote, onClick: () -> Unit) {
    val p = LocalPalette.current
    val ch = q.changePct
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(q.contract.label, style = Type.body.copy(color = p.ink), modifier = Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            Text("%,.2f".format(Locale.ENGLISH, q.last), style = Type.figure.copy(color = p.ink, fontSize = 14.sp))
            Text(when {
                q.lastSession -> "last close"
                ch == null -> ""
                else -> "%+.2f%%".format(Locale.ENGLISH, 100 * ch)
            }, style = Type.bodySmall.copy(color = if ((ch ?: 0.0) >= 0) p.verdigris else p.oxblood, fontSize = 12.sp))
        }
    }
}

/**
 * One MCX future: margin per lot (Zerodha's list when read, else the table; MIS = NRML on MCX), round-trip charges, then
 * Buy / Sell. [onOrder] (buy, lots, limit or null for market, product): paper places it; Live opens the review.
 */
@Composable
internal fun McxFutureSheet(q: McxMarket.Quote, live: Boolean, onOrder: (Boolean, Int, Double?, String) -> Unit, onChart: () -> Unit,
                            onChain: (() -> Unit)?, onClose: () -> Unit,
                            futureExitDays: Int = com.optionslab.engine.mcx.McxExpiry.DEFAULT_FUTURE_EXIT_DAYS) {
    val p = LocalPalette.current
    val c = q.contract
    var buy by remember { mutableStateOf(true) }
    var lots by remember { mutableIntStateOf(1) }
    var limit by remember { mutableStateOf(false) }
    var price by remember { mutableStateOf("%.2f".format(Locale.ENGLISH, q.last)) }
    var product by remember { mutableStateOf("NRML") }
    val units = lots * c.multiplier
    val margin = McxMargin.required(c, buy, lots, q.last, McxMarket.marginFeed())
    val charges = McxCosts.roundTrip(q.last, units, option = false)
    val ok = !limit || price.toDoubleOrNull()?.let { it > 0 } == true
    val sysBars = com.optionslab.app.ui.components.outerBars()
    Dialog(onDismissRequest = onClose, properties = DialogProperties(securePolicy = com.optionslab.app.security.Capture.policy,
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            // The backdrop closes the sheet (a sibling behind it, so the sheet's texts do not merge into it).
            Box(Modifier.fillMaxSize().clickable(role = Role.Button, onClick = onClose))
            Column(
                Modifier.fillMaxWidth()
                    .background(p.card, RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                    // Taps inside the sheet must not reach the backdrop, which closes it.
                    .pointerInput(Unit) { detectTapGestures { } }
                    .clearOfBars(sysBars, top = false)
                    .imePadding()
                    .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 14.dp),
            ) {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    Text(c.label, style = Type.title.copy(color = p.ink, fontSize = 17.sp))
                    Text("%,.2f".format(Locale.ENGLISH, q.last) + (if (q.lastSession) " (last close)" else "") +
                        " · 1 lot = ${c.multiplier} x the price", style = Type.bodySmall.copy(color = p.inkSoft))
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Token("Buy", buy) { buy = true }
                        Token("Sell", !buy) { buy = false }
                        Spacer(Modifier.weight(1f))
                        Token("NRML", product == "NRML") { product = "NRML" }
                        Token("MIS", product == "MIS") { product = "MIS" }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        Text("Lots", style = Type.label.copy(color = p.inkSoft, fontSize = 12.sp), modifier = Modifier.weight(1f))
                        Token("−", false) { if (lots > 1) lots-- }
                        Text("$lots", style = Type.figureLarge.copy(color = p.ink, fontSize = 22.sp), modifier = Modifier.widthIn(min = 48.dp).padding(horizontal = 12.dp))
                        Token("+", false) { if (lots < 10) lots++ }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                        Token("Market", !limit) { limit = false }
                        Token("Limit", limit) { limit = true }
                    }
                    if (limit) OutlinedTextField(price, { price = it.filter { ch -> ch.isDigit() || ch == '.' }.take(12) }, label = { Text("Limit price") },
                        singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    Spacer(Modifier.height(8.dp))
                    Note("Margin about " + (margin?.let { "Rs %,.0f".format(Locale.ENGLISH, it) } ?: "unknown") +
                        " for $lots lot${if (lots == 1) "" else "s"} (Zerodha's; MIS is the same on MCX). Charges about Rs %,.0f a round trip.".format(Locale.ENGLISH, charges))
                    if (Mcx.physical(c.name)) Note("Settles by delivery: the app closes it $futureExitDays trading days before expiry.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                        BrassButton("Chart", tone = p.inkSoft, onClick = onChart)
                        if (onChain != null) BrassButton("Option chain", tone = p.inkSoft, onClick = onChain)
                    }
                }
                Spacer(Modifier.height(10.dp))
                val tone = if (buy) p.verdigris else p.oxblood
                val lotsSaid = "$lots lot${if (lots == 1) "" else "s"}"
                if (live) {
                    BrassButton("Review ${if (buy) "buy" else "sell"} of $lotsSaid", Modifier.fillMaxWidth(), enabled = ok, tone = tone) {
                        onOrder(buy, lots, if (limit) price.toDoubleOrNull() else null, product); onClose()
                    }
                    Note("Live: the order opens for review; it is sent only after you hold the button and confirm.")
                } else {
                    BrassButton("${if (buy) "Buy" else "Sell"} $lotsSaid (paper)", Modifier.fillMaxWidth(), enabled = ok, tone = tone) {
                        onOrder(buy, lots, if (limit) price.toDoubleOrNull() else null, product); onClose()
                    }
                    Note("Paper: simulated with MCX's spread and charges. Nothing reaches Zerodha.")
                }
            }
        }
    }
}
