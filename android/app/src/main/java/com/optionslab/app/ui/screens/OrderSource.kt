package com.optionslab.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type

/**
 * Who placed an order: the strategy ("ORB · leg 1 entry") when this phone's
 * strategy module sent it, otherwise "Manual". [venueId] is "paper:<id>" or
 * "kite:<id>"; [tag] is Zerodha's order tag, which marks strategy orders
 * sent before the phone started keeping names.
 */
fun orderSource(owners: Map<String, String>, venueId: String, tag: String = ""): Pair<String, Boolean> =
    owners[venueId]?.let { "Strategy: $it" to true }
        ?: if (tag == "iraalgostrat") "Strategy order" to true else "Manual" to false

/** The label as a small pill under an order or trade. */
@Composable
fun OrderSourcePill(owners: Map<String, String>, venueId: String, tag: String = "") {
    val p = LocalPalette.current
    val (text, strategy) = orderSource(owners, venueId, tag)
    Text(text, maxLines = 1,
        style = Type.label.copy(color = if (strategy) p.ink else p.inkSoft, fontSize = 11.sp, fontWeight = if (strategy) FontWeight.SemiBold else FontWeight.Medium),
        modifier = Modifier.padding(top = 3.dp).background(p.chip, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp))
}
