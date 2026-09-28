package com.optionslab.app.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max

/** The system bars' height as the app's own screen measured it: status bar on top, navigation / gesture bar below. */
data class Bars(val top: Dp, val bottom: Dp)

/**
 * Read in the screen that opens a full-screen dialog, before the dialog: some phones never tell a dialog window
 * where the gesture bar is (it reads as 0 inside), and the sheet's bottom button then sat under the bar.
 */
@Composable
fun outerBars(): Bars = Bars(
    WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
)

/** Inside the dialog: keeps clear of the bars by whichever is larger, what the dialog is told or what the screen measured. */
@Composable
fun Modifier.clearOfBars(outer: Bars, top: Boolean = true): Modifier {
    val innerTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val innerBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    return padding(top = if (top) max(innerTop, outer.top) else 0.dp, bottom = max(innerBottom, outer.bottom))
}
