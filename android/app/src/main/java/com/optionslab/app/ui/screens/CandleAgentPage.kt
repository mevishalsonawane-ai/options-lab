package com.optionslab.app.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.optionslab.app.data.CandleAgentHost
import com.optionslab.app.data.Market
import com.optionslab.app.data.McxMarket
import com.optionslab.app.ui.Page
import com.optionslab.app.ui.components.LedgerCard
import com.optionslab.app.ui.components.Rule
import com.optionslab.app.ui.components.Token
import com.optionslab.ira.CandlePage
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

const val CANDLE_AGENT_TITLE = "Candle agent"

/**
 * The Candle agent's page (20 Oct 2026; Research → Candles): the latest notable candles (tap one for its explanation: why green
 * or red, where its support and resistance came from, what that kind of candle did historically with n), its shape table
 * against the research, its lessons and notes for Boss, and what it shared with and heard from the other agents. Memory only:
 * the page shows the host's last batch ([CandleAgentHost.view]); nothing here reads a file or trades.
 */
@Composable
internal fun CandleAgentPage() {
    val v by CandleAgentHost.view.collectAsState()
    // 20 Oct, enhancement round 2: the "as of" line's clock and whether NSE or MCX is open, read every 30 s while the page is
    // in view. The markets' calendars may read a file once, so that read is off the main thread; until then, the last batch's.
    var nowMs by remember { mutableStateOf(Market.now().toInstant().toEpochMilli()) }
    var open by remember { mutableStateOf<Boolean?>(null) }
    com.optionslab.app.ui.PollWhileStarted {
        while (true) {
            open = withContext(Dispatchers.Default) {
                runCatching { Market.isOpen() }.getOrDefault(false) || runCatching { McxMarket.isOpen() }.getOrDefault(false)
            }
            nowMs = Market.now().toInstant().toEpochMilli()
            delay(30_000)
        }
    }
    CandleAgentContent(v, nowMs, open ?: v.open)
}

private val HM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("Asia/Kolkata"))

/**
 * The page from plain state (tests draw it directly). [nowMs] and [open] (NSE or MCX open now) are for the "as of" line.
 * 20 Oct, enhancement round 2: an instrument chip row (All, then each instrument the page shows) filters the latest candles,
 * the table, the lessons and the notes; the pick is kept in memory only (as a tab is), back to All when its instrument leaves.
 */
@Composable
internal fun CandleAgentContent(v: CandleAgentHost.View, nowMs: Long = Market.now().toInstant().toEpochMilli(), open: Boolean = v.open) {
    val p = LocalPalette.current
    val soft = Type.bodySmall.copy(color = p.inkSoft)
    val body = Type.bodySmall.copy(color = p.ink)
    val chips = CandlePage.chips(v.latest.map { it.inst } + v.table.map { it.inst } + v.lessons.map { it.inst } + v.notes.map { it.inst })
    var pickKept by rememberSaveable { mutableStateOf(CandlePage.ALL) }
    val pick = CandlePage.picked(pickKept, chips)
    val one = if (pick == CandlePage.ALL) "" else " on $pick"
    Page {
        item { PageTitle(CANDLE_AGENT_TITLE, "Reads each candle, explains it and learns against the research. Knowledge only: it never trades.") }
        item {
            Text(CandlePage.asOf(v.status?.lastBatchMs, nowMs, open, v.released), style = soft,
                modifier = Modifier.testTag("candle-asof").padding(horizontal = 4.dp, vertical = 2.dp))
        }
        if (chips.size > 1) item {
            Row(Modifier.testTag("candle-chips").horizontalScroll(rememberScrollState()).padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                chips.forEach { c -> Token(c, c == pick) { pickKept = c } }
            }
        }
        item {
            LedgerCard(Modifier.testTag("candle-status"), title = "Status") {
                Text(when {
                    v.released -> "Released to free memory: open positions come first. It starts again with its next batch."
                    // 20 Oct, review: it said "hourly otherwise", but a cold start off hours runs nothing until the next session.
                    v.status == null -> "Waiting for its first batch: it reads every 5 minutes while NSE or MCX is open, and starts with the next session."
                    else -> v.status.words()
                }, style = body)
                if (v.growth.isNotEmpty()) Text(v.growth, style = soft)
            }
        }
        item {
            LedgerCard(Modifier.testTag("candle-latest"), title = "Latest candles") {
                val latest = v.latest.filter { CandlePage.shows(pick, it.inst) }
                if (latest.isEmpty()) Text("No notable candle read$one yet.", style = soft)
                latest.take(15).forEachIndexed { i, e ->
                    // 20 Oct, review: each row keyed by its candle, so an open explanation stays on that candle when a batch adds a
                    // newer one above it (keyed by place, it showed the next candle's words under the old title).
                    key(e.read.inst, e.read.tf, e.read.bar.t) {
                        if (i > 0) Rule()
                        ExplainedName(e.title, e.lines, Modifier.fillMaxWidth())
                        Text(e.lines.getOrNull(1).orEmpty(), style = soft, maxLines = 2)
                    }
                }
            }
        }
        item {
            LedgerCard(Modifier.testTag("candle-table"), title = "Shapes against the research") {
                // Each shape, plus the ones read at a level from history and with the big players' hand in them (its own counts).
                val rows = v.table.filter { (it.ctx == "all" || it.ctx.startsWith("hist_") || it.ctx.startsWith("bp_")) && CandlePage.shows(pick, it.inst) }
                    .sortedByDescending { it.cell.n5 }.take(30)
                if (rows.isEmpty()) Text("Nothing counted$one yet: a candle counts once the 10 candles after it are known.", style = soft)
                rows.forEachIndexed { i, c ->
                    if (i > 0) Rule()
                    Text(c.words(), style = body, modifier = Modifier.padding(vertical = 4.dp))
                }
                Text("The research: no candle shape predicted direction by itself (R87: 2 of 17 findings held, both tiny; R85: none for MCX).", style = soft)
            }
        }
        item {
            LedgerCard(Modifier.testTag("candle-lessons"), title = "Lessons") {
                val lessons = v.lessons.filter { CandlePage.shows(pick, it.inst) }
                val notes = v.notes.filter { CandlePage.shows(pick, it.inst) }
                if (lessons.isEmpty()) Text("No lesson$one yet: a shape needs 30 counted candles before it is compared.", style = soft)
                lessons.take(20).forEach { Text(it.text, style = body, modifier = Modifier.padding(vertical = 2.dp)) }
                if (notes.isNotEmpty()) {
                    Text("Notes for Boss (nothing changes by itself):", style = soft, modifier = Modifier.padding(top = 6.dp))
                    notes.forEach { Text(it.text, style = body, modifier = Modifier.padding(vertical = 2.dp)) }
                }
                Text("Lessons come from 5- and 15-minute candles only; a 1-minute candle is explained, never counted as a lesson.",
                    style = soft, modifier = Modifier.padding(top = 6.dp))
            }
        }
        item {
            LedgerCard(Modifier.testTag("candle-talk"), title = "Shared and heard") {
                if (v.exchanges.isEmpty()) Text("Nothing shared or heard yet.", style = soft)
                Column {
                    v.exchanges.take(25).forEach { x ->
                        Text("${HM.format(Instant.ofEpochMilli(x.atMs))} ${x.way.words} ${x.who}: ${x.words}", style = body, maxLines = 3,
                            modifier = Modifier.padding(vertical = 2.dp))
                    }
                }
                Text("Its posts carry no direction, so they never count as a vote in any strategy's decision.", style = soft)
            }
        }
    }
}
