package com.optionslab.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.optionslab.app.data.McxPaperArms
import com.optionslab.app.ui.components.Note
import com.optionslab.app.ui.components.Rule
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import com.optionslab.engine.mcx.McxArmRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

/** The heading of the MCX paper bots' group on Home's Strategies card. */
internal const val MCX_GROUP = "MCX (commodities)"

/** Where the MCX paper bots live (9 Oct, Boss: "it should be on home screen"); the Commodities page says so in one line. */
internal const val MCX_ARMS_WHERE = "MCX paper bots are on Home → Strategies (the MCX (commodities) group)."

/**
 * The MCX paper bots on Home's Strategies card (9 Oct, research M3 / M4 / M2; [McxPaperArms]), in their own
 * "MCX (commodities)" group under the NSE arms: one row each, labelled "Not proven — paper only" with its research numbers
 * above its rules, and its switch - off until Boss turns it on, and on means paper only, always. Reads [McxPaperArms.view]
 * (the one place they are shown; the Commodities page only points here); a switch goes through [McxPaperArms.setArmed]
 * off the main thread.
 */
@Composable
internal fun McxArmRows() {
    val v by McxPaperArms.view.collectAsState(Dispatchers.Main.immediate)
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { kotlinx.coroutines.withContext(Dispatchers.IO) { runCatching { McxPaperArms.refresh() } } }
    McxArmsGroup(v) { arm, on -> scope.launch(Dispatchers.IO) { runCatching { McxPaperArms.setArmed(arm, on) } } }
}

/** The group from plain state ([v]) and the switches' callback (arm, on); it sits in the Strategies card's column. */
@Composable
internal fun McxArmsGroup(v: McxPaperArms.View, onArm: (String, Boolean) -> Unit) {
    val p = LocalPalette.current
    Rule()
    Text(MCX_GROUP, style = Type.title.copy(color = p.ink, fontSize = 15.sp), modifier = Modifier.padding(top = 10.dp))
    Note("Research ideas on paper only: none passed its tests. 1 lot each, never sent to Zerodha, off until you switch one on.",
        Modifier.padding(bottom = 4.dp))
    McxPaperArms.ARMS.forEach { arm -> McxArmRow(arm, v, onArm) }
}

@Composable
private fun McxArmRow(arm: String, v: McxPaperArms.View, onArm: (String, Boolean) -> Unit) {
    val p = LocalPalette.current
    val on = v.isArmed(arm)
    val open = v.open.filter { it.arm == arm }
    Rule()
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(McxPaperArms.label(arm), style = Type.body.copy(color = p.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
            Text(McxArmRules.NOT_PROVEN, style = Type.label.copy(color = p.amber, fontSize = 10.sp, fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(top = 2.dp).background(p.amber.copy(alpha = 0.12f), RoundedCornerShape(50)).padding(horizontal = 7.dp, vertical = 2.dp))
            // The research numbers first, then the rules.
            Text(keepNumbersWhole(McxPaperArms.record(arm)), style = Type.bodySmall.copy(color = p.ink, fontSize = 12.sp),
                modifier = Modifier.padding(top = 4.dp))
            Text(keepNumbersWhole(McxPaperArms.rules(arm)), style = Type.bodySmall.copy(color = p.inkSoft, fontSize = 12.sp))
            Text(if (on) "ON · PAPER ONLY" else "OFF", style = Type.label.copy(color = if (on) p.verdigris else p.inkFaint, fontSize = 10.sp,
                fontWeight = FontWeight.Bold), modifier = Modifier.padding(top = 2.dp))
            open.forEach { o ->
                Text(keepNumbersWhole("${if (o.side > 0) "Long" else "Short"} ${o.symbol} · ${o.qty} @ %.2f · paper".format(Locale.ENGLISH, o.entry)),
                    style = Type.bodySmall.copy(color = p.ink, fontSize = 12.sp))
            }
            if (on) v.status[arm]?.let { s -> Text(keepNumbersWhole(s), style = Type.bodySmall.copy(color = p.inkFaint, fontSize = 11.sp)) }
        }
        Switch(
            modifier = Modifier.semantics { contentDescription = "Paper only: switch ${McxPaperArms.label(arm)}" },
            checked = on,
            onCheckedChange = { x -> onArm(arm, x) },
            colors = SwitchDefaults.colors(checkedTrackColor = p.verdigris, checkedThumbColor = p.card),
        )
    }
}
