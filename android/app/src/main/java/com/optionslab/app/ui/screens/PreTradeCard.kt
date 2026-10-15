package com.optionslab.app.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.optionslab.app.data.DangerToday
import com.optionslab.app.data.Market
import com.optionslab.app.ui.AppModel
import com.optionslab.app.ui.components.LedgerCard
import com.optionslab.app.ui.components.Note
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import com.optionslab.ira.ChargesCheck
import com.optionslab.ira.DangerMeter
import com.optionslab.ira.PreTradeNote
import com.optionslab.ira.SmartStop
import kotlinx.coroutines.Dispatchers

/**
 * Settings → Bot settings → Pre-trade checks (TODO I3 / I4 / I5, 13 Oct 2026): what the charges check, the smart stop and the
 * danger meter do (shadow lines and advice, each with the sample its test had), today's danger reading, and the one switch -
 * half size for PAPER arms on a careful day - which is off unless Boss turns it on. It only ever lowers size and never
 * touches a live order or Liquidity.
 *
 * The danger meter is read only by Jarvis's morning check and its 09:16 re-read, so the reading and the switch show only
 * where those run (Jarvis, IraAlgo); elsewhere the card says so instead (review, 13 Oct). The switch's memory copy for the
 * entry path is kept in step by the settings save ([com.optionslab.app.data.AppSettings.save]), not by this card.
 */
@Composable
internal fun PreTradeCard(model: AppModel) {
    val p = LocalPalette.current
    val s by model.settings.collectAsState(Dispatchers.Main.immediate)
    val held by DangerToday.state.collectAsState()
    val today = runCatching { Market.today() }.getOrElse { java.time.LocalDate.now() }
    LedgerCard(title = "Pre-trade checks", modifier = Modifier.testTag("pretrade-card")) {
        Note("At each paper entry (ORB, ORB Fresh, Sweep, Range Fade, Hero, VIX divergence) the app notes what the costs take of the " +
            "move expected in the next 30 minutes, and the stop the expected range would set. Shown in Order review and in each arm's " +
            "day. None of the three checks passed its held-back test in research R17 (charges check: ${ChargesCheck.HOLDOUT_TRADES} " +
            "trades; smart stop: ${SmartStop.HOLDOUT_TRADES} trades; danger meter: ${DangerMeter.HOLDOUT_DAYS} days), so they skip " +
            "and move nothing. Hero's sample there is tiny (${PreTradeNote.HERO_DESIGN_TRADES} design and " +
            "${PreTradeNote.HERO_HOLDOUT_TRADES} held-back trades). Liquidity is never touched.")
        if (com.optionslab.app.BuildConfig.JARVIS) {
            Text(held?.takeIf { it.day == today }?.reading?.line()
                ?: "Today's danger meter is read in the Good morning check (09:00, provisional before the open) and again at 09:16.",
                style = Type.body.copy(color = p.ink), modifier = Modifier.padding(top = 6.dp).testTag("pretrade-danger"))
            ToggleRow("Half size on careful days (paper arms only)",
                "On a careful or paper-only day, paper arms buy half their lots (at least 1, so a 1-lot arm is unchanged; in practice " +
                    "Hero). Never live orders, never Liquidity. Off by default.", s.dangerHalfSize) { on ->
                DangerToday.halfSize = on
                model.update { it.copy(dangerHalfSize = on) }
            }
        } else {
            Text("The danger meter is read by Jarvis's morning check, which this build does not run: no reading, no half size.",
                style = Type.body.copy(color = p.ink), modifier = Modifier.padding(top = 6.dp).testTag("pretrade-danger"))
        }
    }
}
