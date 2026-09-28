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
 * Who placed an order: the strategy ("Strategy: ORB · entry"), an automatic rule ("Auto: Protection · stop"),
 * or the screen a hand order came from ("Manual · Chart"). [venueId] is "paper:<id>" or "kite:<id>"; [tag]
 * is Zerodha's order tag (null when unknown), which names orders the phone kept no label for.
 * See [com.optionslab.app.data.Origins].
 */
fun orderSource(owners: Map<String, String>, venueId: String, tag: String? = null): Pair<String, Boolean> =
    com.optionslab.app.data.Origins.of(owners, venueId, tag)

/** The label as a small pill under an order or trade. */
@Composable
fun OrderSourcePill(owners: Map<String, String>, venueId: String, tag: String? = null) {
    SourcePill(orderSource(owners, venueId, tag))
}

/** A source label ([orderSource], or a position's [com.optionslab.app.data.Origins.position]) as a small pill. */
@Composable
fun SourcePill(source: Pair<String, Boolean>) {
    val p = LocalPalette.current
    val (text, strategy) = source
    Text(text, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        style = Type.label.copy(color = if (strategy) p.ink else p.inkSoft, fontSize = 11.sp, fontWeight = if (strategy) FontWeight.SemiBold else FontWeight.Medium),
        modifier = Modifier.padding(top = 3.dp).background(p.chip, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp))
}
