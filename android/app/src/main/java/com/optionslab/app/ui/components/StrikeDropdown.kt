package com.optionslab.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.matchParentSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import com.optionslab.engine.fmtG
import java.util.Locale
import kotlin.math.abs

/**
 * A strike chosen from the strikes actually listed, never typed: a field that opens a
 * list centred on the at-the-money strike (marked ATM), with the index level shown.
 */
@Composable
fun StrikeDropdown(strikes: List<Double>, spot: Double?, selected: String, underlying: String, onPick: (String) -> Unit) {
    val p = LocalPalette.current
    var open by remember { mutableStateOf(false) }
    val atm = spot?.let { x -> strikes.minByOrNull { abs(it - x) } }
    val scroll = rememberScrollState()
    val rowPx = with(LocalDensity.current) { 48.dp.toPx() }
    // Open on the ATM strike (or the chosen one), not at the bottom of the ladder.
    LaunchedEffect(open) {
        if (!open) return@LaunchedEffect
        val target = strikes.indexOfFirst { fmtG(it) == selected }.takeIf { it >= 0 } ?: strikes.indexOf(atm).coerceAtLeast(0)
        scroll.scrollTo(((target - 3).coerceAtLeast(0) * rowPx).toInt())
    }
    Box(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        OutlinedTextField(
            value = selected.ifEmpty { if (strikes.isEmpty()) "Loading strikes…" else "Choose a strike" },
            onValueChange = {}, readOnly = true, singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text("Strike" + (spot?.let { " · $underlying ${String.format(Locale.ENGLISH, "%,.0f", it)}" } ?: "")) },
            trailingIcon = { Text(if (open) "▲" else "▼", style = Type.label.copy(color = p.inkSoft)) },
        )
        // The whole field opens the list.
        Box(Modifier.matchParentSize().clickable(enabled = strikes.isNotEmpty()) { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, scrollState = scroll, modifier = Modifier.heightIn(max = 380.dp)) {
            strikes.forEach { k ->
                val label = fmtG(k)
                val isAtm = k == atm
                DropdownMenuItem(
                    text = {
                        Text(label + if (isAtm) "   ATM" else "", style = Type.figure.copy(
                            color = if (isAtm) p.gold else p.ink, fontWeight = if (label == selected || isAtm) FontWeight.Bold else FontWeight.Normal))
                    },
                    onClick = { onPick(label); open = false },
                )
            }
        }
    }
}
