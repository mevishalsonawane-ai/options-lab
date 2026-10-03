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

/** The label as a small pill under an order or trade, with the order's id beside it ("#a1b2c3d4"). */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun OrderSourcePill(owners: Map<String, String>, venueId: String, tag: String? = null) {
    // A row that wraps: the id goes under the pill when there is no room beside it (large fonts, narrow screens).
    androidx.compose.foundation.layout.FlowRow {
        SourcePill(orderSource(owners, venueId, tag))
        com.optionslab.app.data.Origins.shortId(venueId.substringAfter(':'))?.let { id ->
            val p = LocalPalette.current
            Text(id, maxLines = 1, softWrap = false, style = Type.label.copy(color = p.inkSoft, fontSize = 10.sp),
                modifier = Modifier.padding(start = 6.dp, top = 3.dp))
        }
    }
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

/**
 * The P&L of a filled order or trade at the current price [ltp]: what its fill is worth now against what was paid
 * (a buy gains as the price rises, a sell as it falls). Null when there is no fill or no price.
 */
fun fillPnl(buy: Boolean, price: Double, qty: Int, ltp: Double?): Double? =
    if (ltp == null || ltp <= 0 || price <= 0 || qty <= 0) null else (ltp - price) * qty * (if (buy) 1 else -1)

/** A P&L figure on a row: green for a gain, red for a loss, one line. */
@Composable
fun PnlFigure(v: Double?, modifier: Modifier = Modifier) {
    if (v == null) return
    val p = LocalPalette.current
    Text(com.optionslab.app.ui.rs(v, true), maxLines = 1, softWrap = false, modifier = modifier,
        style = Type.figure.copy(color = if (v >= 0) p.verdigris else p.oxblood, fontSize = 13.sp, fontWeight = FontWeight.SemiBold))
}
