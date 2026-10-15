package com.optionslab.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.optionslab.app.data.OrbArms
import com.optionslab.app.ui.components.Expandable
import com.optionslab.app.ui.components.Note
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import com.optionslab.ira.LiquidityGoLive

/** The go-live checklist's fold label. */
internal const val GO_LIVE_HIDE = "Hide the go-live checklist"
internal const val WATCHED_TICK = "Tick: I watched the first live trade (app open, stop and exits confirmed in Kite)"
internal const val WATCHED_UNTICK = "Untick: first live trade watched"
internal const val REENABLE_LIVE = "Re-enable live entries (PIN)"
/** The freeze's short line on Liquidity's row (its words in full behind a tap). */
internal const val FROZEN_SHORT = "Frozen for its paper test · tap for why"
internal const val PAPER_TEST_SHORT = "Its paper test · tap for the freeze"

/**
 * Liquidity BANKNIFTY's paper test under its row (Boss, 12 Oct): the freeze note, the live stop rule (and, once it is reached,
 * the PIN-only re-enable), the day-10 go-live checklist folded ([LiquidityGoLive], each line ✓ / ✗ / — with its number, and
 * Boss's tick for the first live trade), and "Show details": the paper test against the backtest and the fill quality.
 * Information only: nothing here switches to Live.
 */
@Composable
internal fun LiquidityTestLines(t: OrbArms.LiquidityTestView, onWatched: (Boolean) -> Unit, onReenable: () -> Unit) {
    val p = LocalPalette.current
    // COMPACT (15 Oct): the freeze said short; its words in full in a small dialog (OK closes it).
    var freezeOpen by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Text(if (t.frozen) FROZEN_SHORT else PAPER_TEST_SHORT, style = Type.bodySmall.copy(color = if (t.frozen) p.amber else p.inkSoft, fontSize = 12.sp),
        modifier = Modifier.padding(top = 4.dp).testTag("liq-freeze").heightIn(min = 48.dp).clickable(onClickLabel = "Why") { freezeOpen = true }
            .wrapContentHeight())
    if (freezeOpen) ExplainDialog("Liquidity BANKNIFTY's paper test", listOf(t.freeze)) { freezeOpen = false }
    t.stop.forEach { line ->
        Text(keepNumbersWhole(line), style = Type.bodySmall.copy(color = if (t.stopped) p.oxblood else p.inkSoft, fontSize = 12.sp),
            modifier = Modifier.testTag("liq-stop"))
    }
    if (t.stopped) Text(REENABLE_LIVE, style = Type.bodySmall.copy(color = p.oxblood, fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
        modifier = Modifier.clickable { onReenable() }.padding(vertical = 6.dp))
    Expandable("liq-golive", label = t.checklistHead, hideLabel = GO_LIVE_HIDE) {
        t.checklist.forEach { l ->
            Text(keepNumbersWhole(l.toString()), style = Type.bodySmall.copy(fontSize = 12.sp, color = when (l.mark) {
                LiquidityGoLive.Mark.OK -> p.verdigris
                LiquidityGoLive.Mark.NO -> p.oxblood
                LiquidityGoLive.Mark.UNKNOWN -> p.inkSoft
            }), modifier = Modifier.testTag("liq-golive-line"))
        }
        Text(if (t.watched) WATCHED_UNTICK else WATCHED_TICK, style = Type.bodySmall.copy(color = p.ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
            modifier = Modifier.clickable { onWatched(!t.watched) }.padding(vertical = 6.dp))
        Note(LiquidityGoLive.NOTE)
    }
    Expandable("liq-details", label = "Show details", hideLabel = "Hide details") {
        Text(keepNumbersWhole(t.paperTest), style = Type.bodySmall.copy(color = p.ink, fontSize = 12.sp), modifier = Modifier.testTag("liq-papertest"))
        Text(keepNumbersWhole(t.fillsSummary), style = Type.bodySmall.copy(color = p.inkSoft, fontSize = 12.sp), modifier = Modifier.padding(top = 4.dp))
        t.fills.forEach { f -> Text(keepNumbersWhole(f), style = Type.bodySmall.copy(color = p.inkSoft, fontSize = 11.sp), modifier = Modifier.padding(top = 2.dp)) }
    }
}
