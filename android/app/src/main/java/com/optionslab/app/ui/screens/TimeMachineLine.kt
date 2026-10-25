package com.optionslab.app.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.optionslab.app.data.TimeMachineHost
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type

/**
 * The time machine's line under an arm's ticket (22 Oct, [TimeMachineHost]): the bootstrap odds of that entry and its verdict,
 * once its background check is done (nothing while it runs, or when the trade is from an earlier process). Words only, from
 * memory: it never changes the trade.
 */
@Composable
internal fun TimeMachineLine(orderId: String?, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val latest by TimeMachineHost.latest.collectAsState(kotlinx.coroutines.Dispatchers.Main.immediate)
    val r = orderId?.let { id -> latest.firstOrNull { it.entry.ref.endsWith(":$id") } } ?: return
    Text(r.odds?.line() ?: "Time machine (unknown): ${r.note ?: "no replay"}", style = Type.bodySmall.copy(color = p.inkSoft, fontSize = 11.sp),
        modifier = modifier.padding(start = 8.dp, top = 1.dp).testTag("time-machine-line"))
}
