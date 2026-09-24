package com.optionslab.app.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Locale

fun rs(x: Double, sign: Boolean = false): String =
    if (sign) String.format(Locale.ENGLISH, "Rs %+,.0f", x) else String.format(Locale.ENGLISH, "Rs %,.0f", x)

fun rs1(x: Double, sign: Boolean = true): String =
    if (sign) String.format(Locale.ENGLISH, "Rs %+,.1f", x) else String.format(Locale.ENGLISH, "Rs %,.1f", x)

fun pct(x: Double, digits: Int = 2): String = String.format(Locale.ENGLISH, "%.${digits}f%%", 100 * x)
fun num(x: Double, digits: Int = 1): String = String.format(Locale.ENGLISH, "%,.${digits}f", x)

/** Every page scrolls as one column of cards, with room above the tab bar. */
@Composable
fun Page(content: LazyListScope.() -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = rememberLazyListState(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 28.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(14.dp),
        content = content,
    )
}
