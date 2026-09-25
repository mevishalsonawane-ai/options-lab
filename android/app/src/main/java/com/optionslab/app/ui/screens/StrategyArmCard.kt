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
import androidx.compose.material3.AlertDialog
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
    val list by model.strategies.collectAsState()
    var importing by remember { mutableStateOf(false) }
    var reauthFor by remember { mutableStateOf<Long?>(null) }

    LedgerCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Strategies", style = Type.title.copy(color = p.ink, fontSize = 16.sp), modifier = Modifier.weight(1f))
            Text("Import from desktop", style = Type.bodySmall.copy(color = p.ink, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.clickable { importing = true }.padding(4.dp))
        }
        if (list.isEmpty()) {
            Note("No strategies on this phone yet. Import ORB and ORB Fresh (or any strategy) from the desktop app, then arm the ones you want.",
                Modifier.padding(top = 6.dp))
        }
        list.forEachIndexed { i, e ->
            val d = e.def
            val sch = d.scheduler
            val armed = sch?.enabled == true
            if (i > 0) Rule()
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clickable(onClick = onManage)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(d.name, style = Type.body.copy(color = p.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold), maxLines = 1)
                        Spacer(Modifier.width(8.dp))
                        val (label, color) = when {
                            e.running -> "RUNNING" to p.verdigris
                            armed && sch?.defaultMode == RunMode.LIVE -> "ARMED · LIVE" to p.oxblood
                            armed -> "ARMED · PAPER" to p.verdigris
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
                }
                Switch(
                    checked = armed,
                    onCheckedChange = { on ->
                        if (on && s.live) reauthFor = d.id else model.armStrategy(d.id, on)
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = if (s.live) p.oxblood else p.verdigris, checkedThumbColor = p.card),
                )
            }
        }
        if (list.isNotEmpty()) Note(if (s.live) "Arming in Live mode: at the start time you get a notification to confirm with your PIN or fingerprint; exits then run on their own."
            else "Armed strategies start themselves on the paper account at their start time on market days.", Modifier.padding(top = 4.dp))
    }

    reauthFor?.let { id -> Reauth(model, onOk = { reauthFor = null; model.armStrategy(id, true) }, onCancel = { reauthFor = null }) }
    if (importing) ImportDialog(model) { importing = false }
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
