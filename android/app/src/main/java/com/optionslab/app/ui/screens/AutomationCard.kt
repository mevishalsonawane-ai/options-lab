package com.optionslab.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.optionslab.app.data.FlowGate
import com.optionslab.app.data.Market
import com.optionslab.app.data.SmartWorkers
import com.optionslab.app.data.TradeManagerHost
import com.optionslab.app.data.VariantRunner
import com.optionslab.app.ira.Autopilot
import com.optionslab.app.ira.LearningFreeze
import com.optionslab.app.ui.AppModel
import com.optionslab.app.ui.components.LedgerCard
import com.optionslab.app.ui.components.Note
import com.optionslab.app.ui.components.Rule
import com.optionslab.app.ui.components.TextButton
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import com.optionslab.ira.AutomationSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/** The card's title. */
internal const val AUTOMATION_TITLE = "Automation"

/**
 * Where the Automation card's lines come from (11 Oct): the books the five sections already keep in memory, each line worked
 * out only when its own book changes ([AutomationSummary]) and kept only when its words change. Never a file or the settings
 * vault while composing, and never the live order-flow reads or the prices, which move every tick. The sections' heavier
 * readings (the order flow's live reads, the gamma regime, the bots' consensus every 15 s) run only while their sheet is open.
 */
internal object AutomationFeed {
    private fun zone(): ZoneId = runCatching { Market.now().zone }.getOrElse { ZoneId.of("Asia/Kolkata") }

    private val gold get() = com.optionslab.app.BuildConfig.GOLD

    /** The lines now, from memory only (the card's first frame). */
    fun now(today: LocalDate): AutomationSummary.Lines {
        if (gold) return AutomationSummary.Lines()
        val zone = zone()
        val s = SmartWorkers.settings.value
        return AutomationSummary.Lines(
            manager = AutomationSummary.managerLine(TradeManagerHost.records.value, TradeManagerHost.policy.value, today, zone),
            autopilot = if (Autopilot.enabled()) AutomationSummary.autopilotLine(Autopilot.book.value, today) else null,
            lab = if (VariantRunner.enabled()) AutomationSummary.labLine(VariantRunner.variants.value, VariantRunner.forge.value) else null,
            orderFlow = AutomationSummary.orderFlowLine(FlowGate.modes.value),
            brain = AutomationSummary.brainLine(SmartWorkers.brain.value, SmartWorkers.reviews.value, s.paused, s.parked),
            frozen = if (LearningFreeze.enabled()) AutomationSummary.frozenLine(LearningFreeze.state.value) else null,
        )
    }

    /** The lines as their books change: each line apart, then the card's whole set, each only when its words change. */
    fun lines(today: LocalDate): Flow<AutomationSummary.Lines> {
        if (gold) return flowOf(AutomationSummary.Lines())
        val zone = zone()
        val none: Flow<String?> = flowOf(null)
        val manager: Flow<String?> = combine(TradeManagerHost.records, TradeManagerHost.policy) { r, p -> AutomationSummary.managerLine(r, p, today, zone) }
        val autopilot: Flow<String?> = if (!Autopilot.enabled()) none else Autopilot.book.map { AutomationSummary.autopilotLine(it, today) }
        val lab: Flow<String?> = if (!VariantRunner.enabled()) none
            else combine(VariantRunner.variants, VariantRunner.forge) { v, f -> AutomationSummary.labLine(v, f) }
        val flow: Flow<String?> = FlowGate.modes.map { AutomationSummary.orderFlowLine(it) }
        val brain: Flow<String?> = combine(SmartWorkers.brain, SmartWorkers.reviews, SmartWorkers.settings) { c, r, s ->
            AutomationSummary.brainLine(c, r, s.paused, s.parked)
        }
        val frozen: Flow<String?> = if (!LearningFreeze.enabled()) none else LearningFreeze.state.map { AutomationSummary.frozenLine(it) }
        return combine(listOf(manager, autopilot, lab, flow, brain, frozen).map { it.distinctUntilChanged() }) { a ->
            AutomationSummary.Lines(manager = a[0], autopilot = a[1], lab = a[2], orderFlow = a[3], brain = a[4], frozen = a[5])
        }.distinctUntilChanged()
    }

    /** The books read once, off the main thread (what each section's own card used to do on Home). */
    suspend fun load() {
        if (gold) return
        runCatching { TradeManagerHost.ensureLoaded() }
        if (Autopilot.enabled()) runCatching { Autopilot.ensureLoaded() }
        if (VariantRunner.enabled()) runCatching { VariantRunner.ensureLoaded() }
        runCatching { FlowGate.setting("orb") }
        runCatching { SmartWorkers.ensureLoaded() }
        runCatching { SmartWorkers.refreshReviews() }
        if (LearningFreeze.enabled()) runCatching { LearningFreeze.ensureLoaded() }
    }
}

/**
 * Home's Automation card (11 Oct, Boss: trading first, less clutter): the trade manager, Autopilot, the strategy lab, the
 * order flow and the Brain in one line each, and a warning while learning is frozen. A tap on a line opens that section in
 * full, the same section as before with every button and sheet. None in GOLD.
 */
@Composable
fun AutomationHomeCard(model: AppModel) {
    AutomationFeedCard(load = { AutomationFeed.load() }) { s -> AutomationSectionBody(model, s) }
}

/**
 * The card on the app's own books ([AutomationFeed]); [load] reads them once off the main thread (a test passes none), and
 * [section] is what a line's sheet shows. None in GOLD.
 */
@Composable
internal fun AutomationFeedCard(load: suspend () -> Unit, section: @Composable (AutomationSummary.Section) -> Unit) {
    if (com.optionslab.app.BuildConfig.GOLD) return
    val today = remember { runCatching { Market.today() }.getOrElse { LocalDate.now() } }
    val scope = rememberCoroutineScope()
    // A StateFlow derived from the books in memory, alive while the card is composed (nothing kept between screens or tests).
    val lines = remember(scope, today) { AutomationFeed.lines(today).stateIn(scope, SharingStarted.Eagerly, AutomationFeed.now(today)) }
    val l by lines.collectAsState(Dispatchers.Main.immediate)
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { runCatching { load() } } }
    AutomationCardContent(l, section)
}

/** One section in full, as its own Home card showed it before 11 Oct (every button and sheet). */
@Composable
internal fun AutomationSectionBody(model: AppModel, s: AutomationSummary.Section) {
    when (s) {
        AutomationSummary.Section.MANAGER -> TradeManagerSection(model, divider = false)
        AutomationSummary.Section.AUTOPILOT -> AutopilotSection(model)
        AutomationSummary.Section.LAB -> StrategyLabSection(model)
        AutomationSummary.Section.ORDER_FLOW -> OrderFlowSection(model, divider = false)
        AutomationSummary.Section.BRAIN -> BrainSection(divider = false)
    }
}

/**
 * The card from plain state (tests drive it): the title, the frozen warning, one tappable line per section; [section] is what
 * a line's sheet shows ([AutomationSectionBody] in the app).
 */
@Composable
internal fun AutomationCardContent(lines: AutomationSummary.Lines, section: @Composable (AutomationSummary.Section) -> Unit) {
    val p = LocalPalette.current
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val rows = lines.rows()
    LedgerCard(Modifier.testTag("automation-card")) {
        Text(AUTOMATION_TITLE, style = Type.title.copy(color = p.ink, fontSize = 16.sp))
        Note("What the bots' helpers did today. Tap a line for its full section.", Modifier.padding(top = 2.dp))
        lines.frozen?.let { f ->
            // The freeze is Autopilot's: its section has the line and Today's changes (unfreezing is in Settings → Bot settings).
            val toAutopilot = lines.autopilot != null
            Text(f, style = Type.label.copy(color = p.oxblood, fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.testTag("automation-frozen").fillMaxWidth().padding(top = 8.dp)
                    .then(if (toAutopilot) Modifier.heightIn(min = 48.dp).clickable(role = Role.Button) { open = AutomationSummary.Section.AUTOPILOT.key }
                        else Modifier)
                    .padding(vertical = 4.dp))
        }
        rows.forEachIndexed { i, (s, line) ->
            if (i > 0) Rule()
            Row(Modifier.testTag("automation-line-${s.key}").fillMaxWidth().heightIn(min = 48.dp)
                .clickable(role = Role.Button) { open = s.key }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(line, style = Type.body.copy(color = p.ink, fontSize = 14.sp), modifier = Modifier.weight(1f))
                Text("›", style = Type.body.copy(color = p.inkSoft, fontSize = 16.sp), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
    val sel = open?.let { k -> AutomationSummary.Section.values().firstOrNull { it.key == k } }
    if (sel != null) AutomationSheet(sel, onClose = { open = null }) { section(sel) }
}

/**
 * A section's full-height sheet: "Automation" and Close in a row, then the section in one scrolling column, so it fits a phone
 * held either way and at large font. Its own sheets (the records, the change log, the findings) open over it as before.
 */
@Composable
internal fun AutomationSheet(section: AutomationSummary.Section, onClose: () -> Unit, content: @Composable () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose, properties = androidx.compose.ui.window.DialogProperties(
        usePlatformDefaultWidth = false, securePolicy = com.optionslab.app.security.Capture.policy)) {
        val p = LocalPalette.current
        Column(Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.9f).background(p.paper, RoundedCornerShape(16.dp)).testTag("automation-sheet-${section.key}")) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(AUTOMATION_TITLE, style = Type.title.copy(color = p.inkSoft, fontSize = 15.sp), modifier = Modifier.weight(1f))
                TextButton(onClose) { Text("Close") }
            }
            Rule()
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp)) {
                content()
            }
        }
    }
}
