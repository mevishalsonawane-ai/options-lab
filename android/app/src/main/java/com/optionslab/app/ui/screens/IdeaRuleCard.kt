package com.optionslab.app.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.optionslab.app.data.IdeaRuleHost
import com.optionslab.app.ui.AppModel
import com.optionslab.app.ui.components.BrassButton
import com.optionslab.app.ui.components.LedgerCard
import com.optionslab.app.ui.components.Note
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import com.optionslab.ira.IdeaWinRate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The card's title (Settings → Bot settings), as the settings index names it. */
internal const val IDEA_RULE_TITLE = "Automatic ideas: minimum win rate"

/** What the PIN step says. */
internal const val IDEA_RULE_PIN_WHY = "Enter your app PIN to change the minimum win rate for automatic ideas."

/** What the rule does, under the chips. */
internal const val IDEA_RULE_NOTE = "Your rule (28 Oct): forge ideas, strategy lab variants, shadow re-arms and Jarvis's own Pine scripts are " +
    "made, armed or offered only when their own out-of-sample record, after charges and slippage, wins at least this often, with " +
    "enough trades and a profit - on top of every test they already pass. Below it an idea is kept as a note in the change log. " +
    "What you make or switch on yourself, and the arms already running, are not touched."

/**
 * Settings → Bot settings → "Automatic ideas: minimum win rate" (28 Oct): the number, with every change behind the PIN; when
 * the rule was undone in the change log, one button switches it back on (it only holds ideas back, so no PIN).
 */
@Composable
internal fun IdeaRuleSettingCard(model: AppModel) {
    var rule by remember { mutableStateOf(IdeaRuleHost.rule()) }
    var said by remember { mutableStateOf<String?>(null) }
    var pinFor by remember { mutableStateOf<Double?>(null) }
    val scope = rememberCoroutineScope()
    fun run(f: () -> String) {
        scope.launch {
            val msg = withContext(Dispatchers.IO) { runCatching { f() }.getOrElse { "Could not save that." } }
            rule = IdeaRuleHost.rule(); said = msg
        }
    }
    LedgerCard(title = IDEA_RULE_TITLE) {
        IdeaRuleContent(rule, said, onPick = { v -> if (kotlin.math.abs(v - rule.minPct) > 1e-9) pinFor = v },
            onBackOn = { run { IdeaRuleHost.setOn(true) } })
    }
    pinFor?.let { v ->
        Reauth(model, pinOnly = false, why = IDEA_RULE_PIN_WHY, onOk = { pinFor = null; run { IdeaRuleHost.setMinPct(v, pinConfirmed = true) } },
            onCancel = { pinFor = null })
    }
}

/** The card's rows from plain state (tests drive it). */
@Composable
internal fun IdeaRuleContent(rule: IdeaWinRate.Rule, said: String?, onPick: (Double) -> Unit, onBackOn: () -> Unit) {
    val p = LocalPalette.current
    Text(IdeaWinRate.settingLine(rule), style = Type.body.copy(color = p.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
        modifier = Modifier.testTag("idea-rule-line"))
    val choices = IdeaWinRate.CHOICES
    ParamTokens("Minimum win rate", choices.map { IdeaWinRate.pct(it) to (kotlin.math.abs(it - rule.minPct) < 1e-9) }) { i -> onPick(choices[i]) }
    Note(IDEA_RULE_NOTE, Modifier.padding(top = 4.dp))
    if (!rule.on) BrassButton("Switch the rule back on", Modifier.fillMaxWidth().padding(top = 6.dp).testTag("idea-rule-on"), tone = p.inkSoft) { onBackOn() }
    said?.let { Note(it, Modifier.padding(top = 4.dp).testTag("idea-rule-said")) }
}
