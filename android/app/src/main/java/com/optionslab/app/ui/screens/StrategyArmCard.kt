package com.optionslab.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import com.optionslab.app.ui.components.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.optionslab.app.ui.AppModel
import com.optionslab.app.ui.components.BrassButton
import com.optionslab.app.ui.components.LedgerCard
import com.optionslab.app.ui.components.Note
import com.optionslab.app.ui.components.Rule
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import com.optionslab.engine.strategy.RunMode
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/**
 * Home's strategy switches: every saved strategy (e.g. ORB and ORB Fresh
 * imported from the desktop app) with an Arm toggle. Armed means the phone
 * starts it at its entry time on its weekdays, in the current mode; arming
 * live asks for the PIN or fingerprint first.
 */
@Composable
fun StrategyArmCard(model: AppModel, onManage: () -> Unit) {
    val p = LocalPalette.current
    val s by model.settings.collectAsState()
    val all by model.strategies.collectAsState()
    // Imported copies of ORB / ORB Fresh are plain timed baskets; the built-in arms above replace them (TODO A4).
    val list = all.filter { !com.optionslab.app.data.Strategies.needsBreakoutRules(it.def) }
    val replaced = all.size - list.size
    var importing by remember { mutableStateOf(false) }
    val auto by model.strategyAuto.collectAsState()
    val pending by model.strategyPending.collectAsState()
    // Arming asks how orders should go out; a live choice is then confirmed with the PIN or fingerprint.
    var choosing by remember { mutableStateOf<com.optionslab.engine.strategy.StrategyDef?>(null) }
    var reauthArm by remember { mutableStateOf<Pair<Long, Boolean>?>(null) }
    var reauthApprove by remember { mutableStateOf<Long?>(null) }
    val botStopped by model.botStopped.collectAsState()
    var confirmBot by remember { mutableStateOf(false) }

    LedgerCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Strategies", style = Type.title.copy(color = p.ink, fontSize = 16.sp), modifier = Modifier.weight(1f))
            Text("Import from desktop", style = Type.bodySmall.copy(color = p.ink, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.clickable { importing = true }.padding(4.dp))
        }
        // One control for the whole bot: stop it for today, start it again, or clear the kill switch.
        val (botState, botAction, botTone) = when {
            s.guardKill -> Triple("Kill switch ON: all orders blocked", "Clear kill switch", p.oxblood)
            botStopped -> Triple("Bot stopped for today", "Start bot", p.verdigris)
            else -> Triple("Bot running: armed strategies start on time", "Stop bot for today", p.oxblood)
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp).background(botTone.copy(alpha = 0.10f), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(botState, style = Type.bodySmall.copy(color = p.ink, fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
            BrassButton(botAction, tone = botTone) { confirmBot = true }
        }
        OrbRows(model)
        if (replaced > 0) Note("$replaced imported ORB strateg${if (replaced == 1) "y is" else "ies are"} hidden here: the built-in ORB arms above run the real breakout rules. They stay in Trade → Strategies, blocked.",
            Modifier.padding(bottom = 6.dp))
        list.forEachIndexed { i, e ->
            if (i == 0) Rule()
            val d = e.def
            val sch = d.scheduler
            val armed = sch?.enabled == true
            if (i > 0) Rule()
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clickable(onClick = onManage)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(d.name, style = Type.body.copy(color = p.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold), maxLines = 1)
                        Spacer(Modifier.width(8.dp))
                        val how = if (auto[d.id] ?: (sch?.defaultMode != RunMode.LIVE)) "AUTO" else "APPROVE"
                        val blocked = com.optionslab.app.data.Strategies.needsBreakoutRules(d)
                        val (label, color) = when {
                            blocked -> "BLOCKED · needs breakout rules" to p.amber
                            e.running -> "RUNNING" to p.verdigris
                            armed && sch?.defaultMode == RunMode.LIVE -> "ARMED · LIVE · $how" to p.oxblood
                            armed -> "ARMED · PAPER · $how" to p.verdigris
                            else -> "OFF" to p.inkFaint
                        }
                        Text(label, style = Type.label.copy(color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold),
                            modifier = Modifier.background(color.copy(alpha = 0.12f), RoundedCornerShape(50)).padding(horizontal = 7.dp, vertical = 2.dp))
                    }
                    val start = (sch?.startTime ?: d.entryTime)?.toString()
                    val stop = (sch?.autoStopTime ?: d.exitTime)?.toString()
                    val days = (sch?.days?.takeIf { it.isNotEmpty() } ?: DayOfWeek.entries.take(5)).let { ds ->
                        if (ds.size == 5 && ds.containsAll(DayOfWeek.entries.take(5))) "Mon–Fri"
                        else ds.joinToString(" ") { it.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) }
                    }
                    Text(listOfNotNull(d.underlying, start?.let { st -> "$st–${stop ?: "close"}" }, days, "${d.legs.size} leg${if (d.legs.size == 1) "" else "s"}").joinToString(" · "),
                        style = Type.bodySmall.copy(color = p.inkSoft, fontSize = 12.sp), maxLines = 1)
                    if (com.optionslab.app.data.Strategies.needsBreakoutRules(d))
                        Text("Would enter at the start time without a breakout check, so it cannot be armed until the ORB rules are added.",
                            style = Type.bodySmall.copy(color = p.amber, fontSize = 12.sp))
                }
                Switch(
                    enabled = !com.optionslab.app.data.Strategies.needsBreakoutRules(d),
                    checked = armed,
                    onCheckedChange = { on -> if (on) choosing = d else model.armStrategy(d.id, false) },
                    colors = SwitchDefaults.colors(checkedTrackColor = if (s.live) p.oxblood else p.verdigris, checkedThumbColor = p.card),
                )
            }
            pending[d.id]?.let { mode ->
                Column(Modifier.fillMaxWidth().padding(bottom = 10.dp).background(p.amber.copy(alpha = 0.12f), RoundedCornerShape(12.dp)).padding(12.dp)) {
                    Text("Start time reached: waiting for your approval (${if (mode == RunMode.LIVE) "live" else "paper"})",
                        style = Type.bodySmall.copy(color = p.ink, fontWeight = FontWeight.SemiBold))
                    Row(Modifier.padding(top = 8.dp)) {
                        BrassButton("Approve & start", Modifier.weight(1f), tone = if (mode == RunMode.LIVE) p.oxblood else p.verdigris) {
                            if (mode == RunMode.LIVE) reauthApprove = d.id else model.approveStrategy(d.id)
                        }
                        Spacer(Modifier.width(8.dp))
                        BrassButton("Skip today", tone = p.inkSoft) { model.skipStrategy(d.id) }
                    }
                }
            }
        }
        if (list.isNotEmpty()) Note("AUTO places the entry by itself at the start time; APPROVE waits for your tap. Stops, targets and square-off always run by themselves.",
            Modifier.padding(top = 4.dp))
    }

    choosing?.let { d ->
        ApprovalChoice(d.name, live = s.live, onPick = { automatic ->
            choosing = null
            if (s.live) reauthArm = d.id to automatic else model.armStrategy(d.id, true, automatic)
        }, onCancel = { choosing = null })
    }
    reauthArm?.let { (id, automatic) -> Reauth(model, onOk = { reauthArm = null; model.armStrategy(id, true, automatic) }, onCancel = { reauthArm = null }) }
    reauthApprove?.let { id -> Reauth(model, onOk = { reauthApprove = null; model.approveStrategy(id) }, onCancel = { reauthApprove = null }) }
    if (importing) ImportDialog(model) { importing = false }
    if (confirmBot) BotDialog(model, killOn = s.guardKill, stopped = botStopped, anyRunning = list.any { it.running }) { confirmBot = false }
}

/** The desktop app's local API lists strategies and returns each one in full; this is the command that collects them. */
private val EXPORT_COMMAND = """${'$'}k='YOUR_IRAALGO_API_KEY'; ${'$'}u='http://127.0.0.1:5000/api/v1/strategy'
${'$'}ids = (Invoke-RestMethod -Method Post "${'$'}u/list" -ContentType 'application/json' -Body (@{apikey=${'$'}k} | ConvertTo-Json)).data | ForEach-Object { ${'$'}_.id }
${'$'}ids | ForEach-Object { (Invoke-RestMethod -Method Post "${'$'}u/status" -ContentType 'application/json' -Body (@{apikey=${'$'}k; strategy_id=${'$'}_} | ConvertTo-Json)).data } | ConvertTo-Json -Depth 12 | Set-Content -Encoding utf8 iraalgo-strategies.json"""

@Composable
private fun ImportDialog(model: AppModel, onClose: () -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    var text by remember { mutableStateOf("") }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val read = runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
            if (read != null) { model.importStrategies(read); onClose() } else model.say("Could not read that file.")
        }
    }
    AlertDialog(
        onDismissRequest = onClose,
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text("Import from desktop", style = Type.title) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                Text("1. On the PC, with IraAlgo running, open PowerShell and run this (put your IraAlgo API key in the first line):",
                    style = Type.bodySmall.copy(color = p.ink))
                SelectionContainer {
                    Text(EXPORT_COMMAND, style = Type.bodySmall.copy(color = p.ink, fontFamily = FontFamily.Monospace, fontSize = 10.sp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).background(p.chip, RoundedCornerShape(8.dp)).padding(8.dp))
                }
                Text("2. Send iraalgo-strategies.json to this phone and choose it below, or paste its text.", style = Type.bodySmall.copy(color = p.ink))
                Spacer(Modifier.padding(top = 8.dp))
                BrassButton("Choose the .json file", Modifier.fillMaxWidth()) { pick.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
                OutlinedTextField(text, { text = it }, label = { Text("…or paste the JSON") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 90.dp, max = 160.dp))
                Note("Strategies arrive disarmed and paper-only. Arm them on Home; enable live per strategy in Trade → Strategies.", Modifier.padding(top = 6.dp))
            }
        },
        confirmButton = { TextButton({ if (text.isNotBlank()) { model.importStrategies(text); onClose() } }, enabled = text.isNotBlank()) { Text("Import") } },
        dismissButton = { TextButton(onClose) { Text("Cancel") } },
    )
}

/** Asked when a strategy is armed: place orders by itself, or wait for approval. */
@Composable
private fun ApprovalChoice(name: String, live: Boolean, onPick: (Boolean) -> Unit, onCancel: () -> Unit) {
    val p = LocalPalette.current
    AlertDialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text("Arm $name${if (live) " (live)" else " (paper)"}", style = Type.title) },
        text = {
            Column {
                Text("How should its entry orders go out at the start time?", style = Type.bodySmall.copy(color = p.inkSoft))
                ChoiceRow("Automatic", if (live) "Real orders go to Zerodha at the start time without asking. You confirm once now with your PIN or fingerprint."
                    else "The paper entry is placed at the start time without asking.") { onPick(true) }
                ChoiceRow("Ask me to approve", "At the start time you get a notification; the entry is sent only when you tap Approve.") { onPick(false) }
                Note("Either way, stops, targets and the square-off run by themselves.", Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun ChoiceRow(title: String, detail: String, onClick: () -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(top = 10.dp).background(p.chip, RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(12.dp)) {
        Text(title, style = Type.body.copy(color = p.ink, fontWeight = FontWeight.SemiBold))
        Text(detail, style = Type.bodySmall.copy(color = p.inkSoft, fontSize = 12.sp))
    }
}

/** The confirmation behind Home's single bot button. */
@Composable
private fun BotDialog(model: AppModel, killOn: Boolean, stopped: Boolean, anyRunning: Boolean, onClose: () -> Unit) {
    val p = LocalPalette.current
    var alsoStop by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onClose,
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text(when { killOn -> "Clear the kill switch?"; stopped -> "Start the bot?"; else -> "Stop the bot for today?" }, style = Type.title) },
        text = {
            Column {
                Text(when {
                    killOn -> "Orders are allowed again, within your Bot settings limits. Armed strategies start at their times" +
                        if (stopped) " once the bot is started too." else "."
                    stopped -> "Armed strategies start at their scheduled times again today."
                    else -> "No armed strategy starts for the rest of today and waiting approvals are dropped. " +
                        "Open ORB positions are closed now (as on the desktop). Tomorrow the bot runs as usual."
                }, style = Type.bodySmall)
                if (!killOn && !stopped && anyRunning) Row(Modifier.padding(top = 10.dp).clickable { alsoStop = !alsoStop }, verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(alsoStop, { alsoStop = it })
                    Text("Also stop the strategies running now (their positions are closed)", style = Type.bodySmall.copy(color = p.ink))
                }
            }
        },
        confirmButton = {
            TextButton({
                when {
                    killOn -> model.update { it.copy(guardKill = false) }
                    stopped -> model.startBotAgain()
                    else -> model.stopBotForToday(alsoStop)
                }
                onClose()
            }) { Text(when { killOn -> "Clear"; stopped -> "Start"; else -> "Stop for today" }, color = if (killOn || stopped) p.verdigris else p.oxblood) }
        },
        dismissButton = { TextButton(onClose) { Text("Cancel") } },
    )
}
