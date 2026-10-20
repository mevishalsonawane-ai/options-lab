package com.optionslab.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.optionslab.app.data.CandleAgentHost
import com.optionslab.app.ui.Page
import com.optionslab.app.ui.components.LedgerCard
import com.optionslab.app.ui.components.Rule
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
    CandleAgentContent(v)
}

private val HM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("Asia/Kolkata"))

/** The page from plain state (tests draw it directly). */
@Composable
internal fun CandleAgentContent(v: CandleAgentHost.View) {
    val p = LocalPalette.current
    val soft = Type.bodySmall.copy(color = p.inkSoft)
    val body = Type.bodySmall.copy(color = p.ink)
    Page {
        item { PageTitle(CANDLE_AGENT_TITLE, "Reads each candle, explains it and learns against the research. Knowledge only: it never trades.") }
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
                if (v.latest.isEmpty()) Text("No notable candle read yet.", style = soft)
                v.latest.take(15).forEachIndexed { i, e ->
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
                val rows = v.table.filter { it.ctx == "all" || it.ctx.startsWith("hist_") || it.ctx.startsWith("bp_") }
                    .sortedByDescending { it.cell.n5 }.take(30)
                if (rows.isEmpty()) Text("Nothing counted yet: a candle counts once the 10 candles after it are known.", style = soft)
                rows.forEachIndexed { i, c ->
                    if (i > 0) Rule()
                    Text(c.words(), style = body, modifier = Modifier.padding(vertical = 4.dp))
                }
                Text("The research: no candle shape predicted direction by itself (R87: 2 of 17 findings held, both tiny; R85: none for MCX).", style = soft)
            }
        }
        item {
            LedgerCard(Modifier.testTag("candle-lessons"), title = "Lessons") {
                if (v.lessons.isEmpty()) Text("No lesson yet: a shape needs 30 counted candles before it is compared.", style = soft)
                v.lessons.take(20).forEach { Text(it, style = body, modifier = Modifier.padding(vertical = 2.dp)) }
                if (v.notes.isNotEmpty()) {
                    Text("Notes for Boss (nothing changes by itself):", style = soft, modifier = Modifier.padding(top = 6.dp))
                    v.notes.forEach { Text(it, style = body, modifier = Modifier.padding(vertical = 2.dp)) }
                }
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
