package com.optionslab.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.optionslab.app.data.EventDays
import com.optionslab.app.data.HistoryKeeper
import com.optionslab.app.data.PortfolioHost
import com.optionslab.app.ui.components.BrassButton
import com.optionslab.app.ui.components.LedgerCard
import com.optionslab.app.ui.components.Note
import com.optionslab.app.ui.components.TextButton
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import com.optionslab.ira.EventCalendar
import com.optionslab.ira.HistoryVault
import com.optionslab.ira.PortfolioRisk
import com.optionslab.ira.RegimeParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * The Brain card's extra lines (TODO S8, S11, S12, 11 Oct): the event-day note ("Event day: learning ignores today's
 * results"), today's frozen regime, and the "Portfolio" line - a tap opens the arms' correlation matrix. Memory only.
 */
@Composable
internal fun BrainExtras(model: com.optionslab.app.ui.AppModel? = null) {
    if (com.optionslab.app.BuildConfig.GOLD) return
    val p = LocalPalette.current
    val view by PortfolioHost.view.collectAsState(Dispatchers.Main.immediate)
    val cal by EventDays.calendar.collectAsState(Dispatchers.Main.immediate)
    var regime by remember { mutableStateOf<String?>(null) }
    var sheet by remember { mutableStateOf(false) }
    // The regime settings past their shadow, on paper, waiting for Boss's PIN to apply on live too (G9-2).
    var forLive by remember { mutableStateOf<List<RegimeParams.Override>>(emptyList()) }
    var pinFor by remember { mutableStateOf<String?>(null) }
    var said by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(tick) {
        while (true) {
            regime = withContext(Dispatchers.Default) { runCatching { RegimeParams.line() }.getOrNull() }
            forLive = withContext(Dispatchers.IO) { runCatching { com.optionslab.app.data.RegimeHost.readyForLive() }.getOrDefault(emptyList()) }
            kotlinx.coroutines.delay(60_000)
        }
    }
    val today = remember(cal) { runCatching { com.optionslab.app.data.Market.today() }.getOrDefault(LocalDate.now()) }
    EventCalendar.note(cal, today)?.let { Note(it, Modifier.padding(top = 2.dp)) }
    regime?.let { Note("Regime: $it", Modifier.padding(top = 2.dp)) }
    if (model != null) forLive.forEach { o ->
        Note("Regime setting on paper: ${o.words}. It only lowers risk; for live too, your PIN.", Modifier.padding(top = 2.dp))
        BrassButton("Approve for live (PIN)", Modifier.fillMaxWidth().padding(top = 4.dp), tone = p.inkSoft) { pinFor = o.key }
    }
    said?.let { Note(it, Modifier.padding(top = 2.dp)) }
    if (model != null) pinFor?.let { key ->
        Reauth(model, pinOnly = false, why = "Enter your app PIN to apply this regime setting on the live account too.",
            onOk = {
                pinFor = null
                scope.launch {
                    said = withContext(Dispatchers.IO) { runCatching { com.optionslab.app.data.RegimeHost.approveLive(key, pinConfirmed = true) }.getOrElse { "Could not do that just now." } }
                    tick++
                }
            }, onCancel = { pinFor = null })
    }
    Text(view.line, style = Type.bodySmall.copy(color = p.ink), maxLines = 3, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            .semantics { contentDescription = "Portfolio risk: ${view.line} Tap for the correlations." }
            .clickable(role = Role.Button) { sheet = true })
    if (sheet) PortfolioSheet { sheet = false }
}

/** The arms' correlation matrix and each pair's figure (the last 60 trading days, both accounts). */
@Composable
internal fun PortfolioSheet(onClose: () -> Unit) {
    val p = LocalPalette.current
    val view by PortfolioHost.view.collectAsState(Dispatchers.Main.immediate)
    val g = PortfolioHost.guard
    val m = g.matrix
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose, properties = androidx.compose.ui.window.DialogProperties(
        usePlatformDefaultWidth = false, securePolicy = com.optionslab.app.security.Capture.policy)) {
        Column(Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.8f).background(p.paper, RoundedCornerShape(16.dp)).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Portfolio risk", style = Type.title.copy(color = p.ink), modifier = Modifier.weight(1f))
                TextButton(onClose) { Text("Close") }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Note(view.line)
                Note("How alike the arms' daily results are (paper and Zerodha together, after charges), on the days both traded " +
                    "in the last ${PortfolioRisk.WINDOW_DAYS}; ${PortfolioRisk.MIN_COMMON} common days at least. Above " +
                    "${PortfolioRisk.RHO} they move together: a later same-way entry by one of them is blocked when all their stops " +
                    "together would pass the cap. It only ever blocks.", Modifier.padding(top = 4.dp))
                if (m.arms.size >= 2) {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp).horizontalScroll(rememberScrollState())) {
                        Column {
                            Text("", style = Type.label, modifier = Modifier.width(96.dp))
                            m.arms.forEach { a ->
                                Text(PortfolioRisk.label(a), style = Type.label.copy(color = p.ink, fontSize = 11.sp), maxLines = 1,
                                    overflow = TextOverflow.Ellipsis, modifier = Modifier.width(96.dp).padding(vertical = 3.dp))
                            }
                        }
                        m.arms.forEach { b ->
                            Column {
                                Text(PortfolioRisk.label(b).take(8), style = Type.label.copy(color = p.inkSoft, fontSize = 10.sp), maxLines = 1,
                                    modifier = Modifier.width(52.dp))
                                m.arms.forEach { a ->
                                    val r = m.of(a, b)
                                    val tone = when { r == null -> p.inkFaint; a == b -> p.inkSoft; r > PortfolioRisk.RHO -> p.oxblood; else -> p.ink }
                                    Text(r?.let { "%.2f".format(java.util.Locale.ENGLISH, it) } ?: "—",
                                        style = Type.label.copy(color = tone, fontSize = 11.sp, fontWeight = if (r != null && a != b && r > PortfolioRisk.RHO) FontWeight.Bold else FontWeight.Normal),
                                        modifier = Modifier.width(52.dp).padding(vertical = 3.dp))
                                }
                            }
                        }
                    }
                }
                view.pairs.take(20).forEach { Note(it, Modifier.padding(top = 2.dp)) }
                val blocks = remember { runCatching { g.blocksToday(com.optionslab.app.data.Market.today()) }.getOrDefault(emptyList()) }
                if (blocks.isNotEmpty()) {
                    Text("BLOCKED TODAY", style = Type.label.copy(color = p.inkSoft, fontSize = 10.sp), modifier = Modifier.padding(top = 12.dp))
                    blocks.forEach { Note(it.why, Modifier.padding(top = 2.dp)) }
                }
            }
        }
    }
}

/** Bot settings: the portfolio guard's cap and switch (TODO S12). */
@Composable
internal fun PortfolioSettingCard() {
    val view by PortfolioHost.view.collectAsState(Dispatchers.Main.immediate)
    val scope = rememberCoroutineScope()
    var said by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { runCatching { PortfolioHost.refresh() } } }
    fun save(f: () -> String) { scope.launch(Dispatchers.IO) { val m = runCatching { f() }.getOrElse { "Could not save that." }; withContext(Dispatchers.Main) { said = m } } }
    LedgerCard(title = "Portfolio guard") {
        Note("When arms that move together (correlation above ${PortfolioRisk.RHO}) open the same way on one day, a later one is " +
            "blocked if all their stops together would lose more than this share of capital. Paper and Zerodha alike; it never adds a trade.")
        ToggleRow("Block correlated entries over the cap", if (view.on) "On" else "Off: correlated arms may all open on one day", view.on) { on -> save { PortfolioHost.setOn(on) } }
        ParamTokens("Cap for correlated arms together (% of capital)", PortfolioRisk.CAP_CHOICES.map {
            (if (it % 1.0 == 0.0) "${it.toInt()}%" else "$it%") to (kotlin.math.abs(it - view.capPct) < 1e-9)
        }) { i -> save { PortfolioHost.setCapPct(PortfolioRisk.CAP_CHOICES[i]) } }
        if (view.capRs > 0) Note("Now: ${PortfolioRisk.rs(view.capRs)} of your capital.")
        said?.let { Note(it, Modifier.padding(top = 4.dp)) }
    }
}

/**
 * Bot settings: the same-direction cap (19 Oct, Boss: "Resolve the issues"): its paper switch (on by default; each flip logged in
 * the Autopilot change log, one tap undoes it), its number, its live switch (off: live only logs what it would refuse), the
 * automatic index options open now, and today's refused entries by arm (Liquidity's counted apart).
 */
@Composable
internal fun SameDirectionCapCard() {
    val view by com.optionslab.app.data.ExposureCapHost.view.collectAsState(Dispatchers.Main.immediate)
    val scope = rememberCoroutineScope()
    var said by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { runCatching { com.optionslab.app.data.ExposureCapHost.refresh() } } }
    fun save(f: () -> String) { scope.launch(Dispatchers.IO) { val m = runCatching { f() }.getOrElse { "Could not save that." }; withContext(Dispatchers.Main) { said = m } } }
    LedgerCard(title = "Same-direction cap") {
        Note("At most this many automatic option positions may bet the same way on the indices at once (long calls up, long puts " +
            "down), across all arms. A later entry is refused and kept as a would-be trade. Exits and your own orders are never capped.")
        ToggleRow("Cap automatic paper entries", if (view.paperOn) "On: at most ${view.max} the same way" else "Off: any number may open the same way", view.paperOn) { on ->
            save { com.optionslab.app.data.ExposureCapHost.setPaperOn(on) }
        }
        ParamTokens("Most open the same way", com.optionslab.ira.SameDirectionCap.CHOICES.map { "$it" to (it == view.max) }) { i ->
            save { com.optionslab.app.data.ExposureCapHost.setMax(com.optionslab.ira.SameDirectionCap.CHOICES[i]) }
        }
        ToggleRow("Also refuse the bots' live entries", if (view.liveOn) "On: live entries past the cap are refused" else "Off: live only logs what it would refuse", view.liveOn) { on ->
            save { com.optionslab.app.data.ExposureCapHost.setLiveOn(on) }
        }
        if (view.openNow.isNotEmpty()) Note("Open now (paper): " + view.openNow.joinToString(", "), Modifier.padding(top = 4.dp))
        if (view.line.isNotEmpty()) Note(view.line, Modifier.padding(top = 4.dp))
        said?.let { Note(it, Modifier.padding(top = 4.dp)) }
    }
}

/**
 * 20 Oct, enhancement round 1 (idea 4): Bot settings' account stop rule (-Rs 40,000 from the best, -Rs 13,000 a month) over the whole
 * Zerodha account. Its switch is off by default (shadow: what it would refuse is only logged); on, the bots' new live entries are
 * refused once it is reached. Boss's own orders and every exit are never refused. Restarting the count after it is reached
 * takes the PIN.
 */
@Composable
internal fun AccountStopCard(model: com.optionslab.app.ui.AppModel) {
    val view by com.optionslab.app.data.AccountStopHost.view.collectAsState(Dispatchers.Main.immediate)
    val scope = rememberCoroutineScope()
    var said by remember { mutableStateOf<String?>(null) }
    var askPin by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { runCatching { com.optionslab.app.data.AccountStopHost.refresh() } } }
    fun save(f: () -> String) { scope.launch(Dispatchers.IO) { val m = runCatching { f() }.getOrElse { "Could not save that." }; withContext(Dispatchers.Main) { said = m } } }
    AccountStopCardContent(view, said, onSwitch = { on -> save { com.optionslab.app.data.AccountStopHost.setOn(on) } }, onRestart = { askPin = true })
    if (askPin) Reauth(model, onOk = { askPin = false; save { com.optionslab.app.data.AccountStopHost.restart(pinConfirmed = true) } },
        onCancel = { askPin = false }, why = "Enter your app PIN to restart the account stop rule's count. It then counts again from the next trading day.")
}

internal const val ACCOUNT_STOP_RESTART = "Restart the count (PIN)"

/** The card from plain state (tests drive it). */
@Composable
internal fun AccountStopCardContent(view: com.optionslab.app.data.AccountStopHost.View, said: String?, onSwitch: (Boolean) -> Unit, onRestart: () -> Unit) {
    LedgerCard(title = "Account stop rule") {
        Note("Your rule: once the Zerodha account is -Rs 40,000 from its best or -Rs 13,000 in a calendar month (Zerodha's day " +
            "figures, less the day's charges), the bots take no new live entry. Your own orders and every exit always go.")
        ToggleRow("Refuse the bots' live entries once reached", if (view.on) "On: refused once reached" else "Off: live only logs what it would refuse", view.on, keepSub = true, onChange = onSwitch)
        if (view.line.isNotEmpty()) Note(view.line, Modifier.padding(top = 4.dp))
        if (view.reached) BrassButton(ACCOUNT_STOP_RESTART, Modifier.fillMaxWidth().padding(top = 4.dp)) { onRestart() }
        said?.let { Note(it, Modifier.padding(top = 4.dp)) }
    }
}

/**
 * 20 Oct, enhancement round 2 (idea 1): Bot settings' exit coordinator. Off by default (shadow: what it would do is only logged);
 * on, two automatic exits on one contract never read Zerodha's books at once, and a read missing an exit just sent is read again.
 * It never refuses or resizes an exit, and past a short wait an exit always goes.
 */
@Composable
internal fun ExitCoordinatorCard() {
    val view by com.optionslab.app.data.ExitCoordinator.view.collectAsState(Dispatchers.Main.immediate)
    val scope = rememberCoroutineScope()
    var said by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { runCatching { com.optionslab.app.data.ExitCoordinator.refresh() } } }
    ExitCoordinatorCardContent(view, said, onLagSwitch = { on ->
        scope.launch(Dispatchers.IO) {
            val m = runCatching { com.optionslab.app.data.ExitCoordinator.setLagOn(on) }.getOrElse { "Could not save that." }
            withContext(Dispatchers.Main) { said = m }
        }
    }) { on ->
        scope.launch(Dispatchers.IO) {
            val m = runCatching { com.optionslab.app.data.ExitCoordinator.setOn(on) }.getOrElse { "Could not save that." }
            withContext(Dispatchers.Main) { said = m }
        }
    }
}

/** The card from plain state (tests drive it). */
@Composable
internal fun ExitCoordinatorCardContent(view: com.optionslab.app.data.ExitCoordinator.View, said: String?, onLagSwitch: (Boolean) -> Unit = {},
                                        onSwitch: (Boolean) -> Unit) {
    LedgerCard(title = "One exit at a time") {
        Note("When two of the app's exits (an arm, a stop, the expiry square-off, a strategy) want to sell the same contract at once, " +
            "each waits its turn and reads Zerodha's books again, so one contract is never sold twice. Your own exits never wait.")
        ToggleRow("Make exits take turns", if (view.on) "On: one exit at a time per contract" else "Off: only logs what it would do", view.on, keepSub = true, onChange = onSwitch)
        if (view.line.isNotEmpty()) Note(view.line, Modifier.padding(top = 4.dp))
        // 21 Oct, enhancement round 3 (idea 1): the positions-lag guard, its own switch (off: logged only).
        ToggleRow("Never sell a filled exit twice", if (view.lagOn) "On: a positions read behind an exit's fill is read again"
            else "Off: only logs what it would do", view.lagOn, keepSub = true, onChange = onLagSwitch)
        Note("Zerodha's positions can trail an exit that already filled by a moment. On, the next exit reads them again, and " +
            "counts the fill as sold if they still trail; it only ever sends less.", Modifier.padding(top = 2.dp))
        if (view.lagLine.isNotEmpty()) Note(view.lagLine, Modifier.padding(top = 4.dp))
        said?.let { Note(it, Modifier.padding(top = 4.dp)) }
    }
}

/**
 * 21 Oct, enhancement round 4 (idea 1): Bot settings' "Stop and target as one GTT". Off by default; switching it on takes the PIN
 * (a GTT's stop sends a limit order, which can miss in a gap); off never does. On, a carried (NRML) position's stop and target
 * rest at Zerodha all day as one two-leg GTT, so a dead phone never leaves the target resting after the stop filled.
 */
@Composable
internal fun DayOcoCard(model: com.optionslab.app.ui.AppModel) {
    val view by com.optionslab.app.data.DayOco.view.collectAsState(Dispatchers.Main.immediate)
    val scope = rememberCoroutineScope()
    var said by remember { mutableStateOf<String?>(null) }
    var askPin by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { runCatching { com.optionslab.app.data.DayOco.refresh() } } }
    fun save(f: () -> String) { scope.launch(Dispatchers.IO) { val m = runCatching { f() }.getOrElse { "Could not save that." }; withContext(Dispatchers.Main) { said = m } } }
    DayOcoCardContent(view, said) { on -> if (on) askPin = true else save { com.optionslab.app.data.DayOco.setOn(false) } }
    if (askPin) Reauth(model, onOk = { askPin = false; save { com.optionslab.app.data.DayOco.setOn(true, pinConfirmed = true) } },
        onCancel = { askPin = false }, why = "Enter your app PIN to rest a carried position's stop and target as one GTT at Zerodha.")
}

/** The card from plain state (tests drive it). */
@Composable
internal fun DayOcoCardContent(view: com.optionslab.app.data.DayOco.View, said: String?, onSwitch: (Boolean) -> Unit) {
    LedgerCard(title = "Stop and target as one GTT") {
        Note("A carried (NRML) position's stop and target rest at Zerodha as two orders in the day; the app cancels one when the " +
            "other fills. With the phone off, both would stay. On, they rest all day as one GTT: whichever fires, Zerodha cancels " +
            "the other itself. Intraday trades are unchanged.")
        ToggleRow("Stop and target as one GTT in the day", if (view.on) "On: one GTT, all day and overnight" else "Off: two orders in the day (as before)",
            view.on, keepSub = true, onChange = onSwitch)
        Note("The trade-off: a GTT's stop is checked by Zerodha's server and then sends a limit order, which can miss in a sharp gap. " +
            "Switching it on takes your PIN.", Modifier.padding(top = 2.dp))
        said?.let { Note(it, Modifier.padding(top = 4.dp)) }
    }
}

/**
 * 21 Oct, enhancement round 4 (idea 3): Bot settings' payout-aware drawdown. Off by default (shadow: what it would say is logged).
 * On, when a drawdown from the peak stops live entries and the payouts since that peak alone explain it, the card offers to lower
 * the peak by them, with the PIN. It never clears the kill switch: Boss does that.
 */
@Composable
internal fun PayoutDrawdownCard(model: com.optionslab.app.ui.AppModel) {
    val view by com.optionslab.app.data.PayoutWatch.view.collectAsState(Dispatchers.Main.immediate)
    val scope = rememberCoroutineScope()
    var said by remember { mutableStateOf<String?>(null) }
    var askPin by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { runCatching { com.optionslab.app.data.PayoutWatch.refresh() } } }
    fun save(f: () -> String) { scope.launch(Dispatchers.IO) { val m = runCatching { f() }.getOrElse { "Could not save that." }; withContext(Dispatchers.Main) { said = m } } }
    PayoutDrawdownCardContent(view, said, onSwitch = { on -> save { com.optionslab.app.data.PayoutWatch.setOn(on) } }, onLower = { askPin = true })
    if (askPin) Reauth(model, onOk = { askPin = false; save { com.optionslab.app.data.PayoutWatch.lowerPeak(pinConfirmed = true) } },
        onCancel = { askPin = false }, why = "Enter your app PIN to lower the Zerodha drawdown peak by the money taken out.")
}

/** The offer's button: "Lower the peak by Rs N (PIN)". */
internal fun payoutLowerLabel(rs: Double): String = "Lower the peak by Rs %,.0f (PIN)".format(java.util.Locale.ENGLISH, rs)

/** The card from plain state (tests drive it). */
@Composable
internal fun PayoutDrawdownCardContent(view: com.optionslab.app.data.PayoutWatch.View, said: String?, onSwitch: (Boolean) -> Unit, onLower: () -> Unit) {
    LedgerCard(title = "Payouts and the drawdown") {
        Note("Money taken out of Zerodha lowers the next day's opening balance, which reads as a drawdown from the peak and can turn " +
            "the kill switch on. The app keeps each day's payout and counts those made since the peak.")
        ToggleRow("Count payouts in the drawdown", if (view.on) "On: said beside the refusal, and offered to you" else "Off: only logs what it would say",
            view.on, keepSub = true, onChange = onSwitch)
        Note("It never clears the kill switch or keeps it off by itself: on, it offers to lower the peak by the payouts, with your PIN.",
            Modifier.padding(top = 2.dp))
        if (view.line.isNotEmpty()) Note(view.line, Modifier.padding(top = 4.dp))
        view.offer?.let { rs -> BrassButton(payoutLowerLabel(rs), Modifier.fillMaxWidth().padding(top = 4.dp)) { onLower() } }
        said?.let { Note(it, Modifier.padding(top = 4.dp)) }
    }
}

/** Bot settings: the market history kept on the phone, its use and its cap (TODO S5). */
@Composable
internal fun HistoryVaultCard() {
    val scope = rememberCoroutineScope()
    var usage by remember { mutableStateOf("Reading…") }
    var cap by remember { mutableStateOf(HistoryVault.DEFAULT_CAP_MB) }
    var last by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val (u, c) = withContext(Dispatchers.IO) {
            runCatching { HistoryKeeper.usageLine() }.getOrDefault("Not readable just now.") to
                (runCatching { HistoryKeeper.vault()?.capMb }.getOrNull() ?: HistoryVault.DEFAULT_CAP_MB)
        }
        usage = u; cap = c; last = HistoryKeeper.lastRun
    }
    LedgerCard(title = "Market history on the phone") {
        Note("Each trading day after 15:45 the phone keeps the day's 1-minute bars - the six indices with India VIX, the index " +
            "futures with OI, the options it watched (ATM ±5) and their closing chain, and MCX's near-month futures - compressed, " +
            "for the strategy lab and the checks. The oldest days go first when the cap is reached.")
        Note("Used: $usage", Modifier.padding(top = 4.dp))
        last?.let { Note("Last run: $it", Modifier.padding(top = 2.dp)) }
        ParamTokens("Keep at most", HistoryVault.CAP_CHOICES.map { "$it MB" to (it == cap) }) { i ->
            val mb = HistoryVault.CAP_CHOICES[i]
            cap = mb
            scope.launch(Dispatchers.IO) {
                runCatching { HistoryKeeper.setCapMb(mb) }
                val u = runCatching { HistoryKeeper.usageLine() }.getOrDefault(usage)
                withContext(Dispatchers.Main) { usage = u }
            }
        }
    }
}

/** Bot settings: the event days learning leaves out (TODO S11): the bundled and rule-made ones and Boss's own. */
@Composable
internal fun EventCalendarCard() {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val cal by EventDays.calendar.collectAsState(Dispatchers.Main.immediate)
    var said by remember { mutableStateOf<String?>(null) }
    var date by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { runCatching { EventDays.refresh() } } }
    val upcoming = remember(cal) { runCatching { EventDays.upcoming(120) }.getOrDefault(emptyList()) }
    fun save(f: () -> String) { scope.launch(Dispatchers.IO) { val m = runCatching { f() }.getOrElse { "Could not save that." }; withContext(Dispatchers.Main) { said = m } } }
    LedgerCard(title = "Event days") {
        Note("On these days learning leaves the results out of its evidence (kept apart as event-day results): RBI policy, the " +
            "Budget, election results, the index rebalances and an expiry moved by a holiday. Tentative dates are not yet " +
            "published officially: correct them here.")
        if (upcoming.isEmpty()) Note("None in the next 4 months.", Modifier.padding(top = 4.dp))
        upcoming.take(12).forEach { e ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(EventCalendar.line(e), style = Type.bodySmall.copy(color = p.ink), modifier = Modifier.weight(1f))
                TextButton({ save { EventDays.remove(e.day) } }) { Text("✕", style = Type.label.copy(color = p.oxblood)) }
            }
        }
        OutlinedTextField(date, { date = it.filter { c -> c.isDigit() || c == '-' }.take(10) },
            label = { Text("Add an event day (yyyy-mm-dd)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(name, { name = it.take(60) }, label = { Text("What it is (e.g. election results)") }, singleLine = true,
            modifier = Modifier.fillMaxWidth())
        val parsed = runCatching { LocalDate.parse(date) }.getOrNull()
        BrassButton("Add", Modifier.fillMaxWidth().padding(top = 4.dp), tone = p.inkSoft, enabled = parsed != null) {
            val d = parsed ?: return@BrassButton
            val n = name
            save { EventDays.add(d, n) }
            date = ""; name = ""
        }
        said?.let { Note(it, Modifier.padding(top = 4.dp)) }
    }
}
