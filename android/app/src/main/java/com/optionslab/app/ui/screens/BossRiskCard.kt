package com.optionslab.app.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.optionslab.app.data.BossRiskHost
import com.optionslab.app.data.BossRiskHost.Rule
import com.optionslab.app.ui.components.BrassButton
import com.optionslab.app.ui.components.LedgerCard
import com.optionslab.app.ui.components.Note
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 25 Oct (Boss: "Do whatever you think better for application", on the app audit's decisions): Bot settings' card for the new risk
 * rules ([BossRiskHost]). Each paper rule is on (Boss's decision; logged in the Autopilot change timeline, one tap back there too);
 * switching one of the risk LIMITS off loosens it, so it asks the PIN (as the paper loss limit does); switching on never waits. Each
 * live switch is off by default: shadow, what it would refuse is only logged.
 */
@Composable
internal fun BossRiskCard(model: com.optionslab.app.ui.AppModel) {
    val view by BossRiskHost.view.collectAsState(Dispatchers.Main.immediate)
    val scope = rememberCoroutineScope()
    var said by remember { mutableStateOf<String?>(null) }
    var askPin by remember { mutableStateOf<(() -> String)?>(null) }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { runCatching { BossRiskHost.refreshView() } } }
    fun save(f: () -> String) { scope.launch(Dispatchers.IO) { val m = runCatching { f() }.getOrElse { "Could not save that." }; withContext(Dispatchers.Main) { said = m } } }
    BossRiskCardContent(view, said,
        onPaper = { rule, on ->
            // Off loosens a limit: the PIN first when the guard's rule asks it (as the paper loss limit's row).
            if (!on && rule.pinToLoosen && com.optionslab.app.security.GuardPin.asks(true, model.settings.value.live))
                askPin = { BossRiskHost.setPaper(rule, false, pinConfirmed = true) }
            else save { BossRiskHost.setPaper(rule, on, pinConfirmed = !rule.pinToLoosen || on) }
        },
        onLive = { rule, on -> save { BossRiskHost.setLive(rule, on) } },
        onRestart = { askPin = { BossRiskHost.restartAccount(pinConfirmed = true) } })
    askPin?.let { action ->
        Reauth(model, onOk = { askPin = null; save(action) }, onCancel = { askPin = null }, why = BOSS_RISK_PIN)
    }
}

internal const val BOSS_RISK_PIN = "Enter your app PIN to loosen a paper risk limit."
internal const val BOSS_RISK_RESTART = "Restart the paper account stop's count (PIN)"
internal const val BOSS_RISK_TITLE = "Risk rules (your 25 Oct decisions)"

/** Each paper rule's row title. */
internal fun bossPaperTitle(r: Rule): String = when (r) {
    Rule.ACCOUNT -> "Paper: account stop rule (-₹40,000 from the best, -₹13,000 a month)"
    Rule.WEEKLY -> "Paper: weekly loss stop (the amount is on the Account guard card)"
    Rule.RISK -> "Paper: 2% risk cap per trade (size down, or skip)"
    Rule.OPPOSITE -> "Paper: no opposite bets between arms on one index"
    Rule.CAPPED -> "Paper: Liquidity buys with h14's capped limit (+0.5%, 3 minutes)"
    Rule.EXPIRY -> "Paper: Liquidity skips its index's expiry day"
    Rule.SPREAD -> "Spread check (shadow only)"
}

/** Each live rule's row title. */
internal fun bossLiveTitle(r: Rule): String = "Live: ${r.words} refuses the bots' entries"

/** The card from plain state (tests drive it). */
@Composable
internal fun BossRiskCardContent(view: BossRiskHost.View, said: String?, onPaper: (Rule, Boolean) -> Unit, onLive: (Rule, Boolean) -> Unit,
                                 onRestart: () -> Unit) {
    LedgerCard(title = BOSS_RISK_TITLE) {
        Note("On paper they act now and are logged in Autopilot's change log (one tap undoes each). Live is in shadow: what each would " +
            "refuse is logged, nothing changes, until you switch it on. Open positions always keep their exits; your own orders are never refused.")
        for (r in BossRiskHost.PAPER_RULES) {
            val on = view.paper[r] == true
            ToggleRow(bossPaperTitle(r), if (on) "On" else if (r.pinToLoosen) "Off (switching it off asks your PIN)" else "Off", on, keepSub = true) { onPaper(r, it) }
        }
        for (r in BossRiskHost.LIVE_RULES) {
            val on = view.live[r] == true
            ToggleRow(bossLiveTitle(r), if (on) "On: refused" else "Off: shadow, logged only", on, keepSub = true) { onLive(r, it) }
        }
        view.lines.forEach { Note(it, Modifier.padding(top = 4.dp)) }
        if (view.accountReached) BrassButton(BOSS_RISK_RESTART, Modifier.fillMaxWidth().padding(top = 4.dp)) { onRestart() }
        said?.let { Note(it, Modifier.padding(top = 4.dp)) }
    }
}
