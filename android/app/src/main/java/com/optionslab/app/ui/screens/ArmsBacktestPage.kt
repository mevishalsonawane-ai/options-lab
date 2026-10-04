package com.optionslab.app.ui.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.optionslab.app.ui.AppModel
import com.optionslab.app.ui.Load
import com.optionslab.app.ui.Page
import com.optionslab.app.ui.components.BrassButton
import com.optionslab.app.ui.components.FullSpinner
import com.optionslab.app.ui.components.LedgerCard
import com.optionslab.app.ui.components.LedgerLine
import com.optionslab.app.ui.components.Note
import com.optionslab.app.ui.rs
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import com.optionslab.engine.orb.ArmsBacktest

@Composable
fun ArmsBacktestPage(model: AppModel) {
    val st by model.armsBacktest.collectAsState(kotlinx.coroutines.Dispatchers.Main.immediate)
    ArmsBacktestContent(st, model::runArmsBacktest)
}

/** The ORB arms side by side on every recorded BANKNIFTY day (tests drive it without an [AppModel]). */
@Composable
internal fun ArmsBacktestContent(st: Load<ArmsBacktest.Result>, onRun: () -> Unit) {
    val p = LocalPalette.current
    Page {
        item { PageTitle("Arms backtest", "ORB, ORB Fresh, ORB Sweep and Range Fade on every recorded day") }
        item {
            LedgerCard {
                Note("Each arm's own rules on 5-minute BANKNIFTY bars, filled on the real option minute bars: the entry at the " +
                    "next minute after the signal bar, its own stop and target, the 15:10 exit, 0.5 point slippage a side and " +
                    "Rs 40 a round trip, one lot. The bundled month plus every day this phone has harvested.")
                Spacer(Modifier.height(10.dp))
                BrassButton("Run", Modifier.fillMaxWidth(), busy = st is Load.Busy) { onRun() }
            }
        }
        when (val s = st) {
            is Load.Busy -> item { LedgerCard { FullSpinner(s.label, s.progress) } }
            is Load.Failed -> item { LedgerCard { Note(s.why) } }
            is Load.Done -> {
                val r = s.value
                item {
                    LedgerCard(title = "${r.days} days") {
                        Text("${r.firstDay} .. ${r.lastDay}", style = Type.figure.copy(fontSize = 12.sp, color = p.inkSoft))
                    }
                }
                r.rows.forEach { row ->
                    item {
                        LedgerCard(title = row.arm.label + if (row.arm.paperOnly) " · paper only" else "",
                            accent = if (row.net > 0 && row.firstHalf > 0 && row.secondHalf > 0) p.verdigris else if (row.net < 0) p.oxblood else null) {
                            LedgerLine("Trades · winners", "${row.trades} · ${row.winPct}%")
                            LedgerLine("Net after costs", rs(row.net, sign = true), if (row.net >= 0) p.verdigris else p.oxblood)
                            LedgerLine("Per trade", rs(row.avg, sign = true))
                            LedgerLine("t-stat", row.t?.let { "%.2f".format(it) } ?: "-")
                            LedgerLine("First half · second half", "${rs(row.firstHalf, sign = true)} · ${rs(row.secondHalf, sign = true)}")
                        }
                    }
                }
                item {
                    LedgerCard {
                        Note("Green: profitable in both halves. A t-stat under 2 is not yet an edge, and a month of range days " +
                            "favours the fades: the more days the phone harvests, the more this table is worth.")
                    }
                }
            }
            Load.Idle -> Unit
        }
    }
}
