package com.optionslab.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type

/** The test tag of [Expandable]'s toggle for [key]: tests tap it ("expand:<key>"). */
fun expandTag(key: String): String = "expand:$key"

/**
 * Boss (12 Oct): "there should be 'show details' and when we click on that it should expand". The app's ONE fold for long
 * secondary detail: [summary] (the key line, always shown), then a "Show details ▾" / "Hide details ▴" text button, then
 * [details] only while open. Folded by default; open or folded is kept per [key] across recomposition and rotation
 * (rememberSaveable, keyed so items in a loop never share a state). The toggle is a button to TalkBack (role, click label,
 * "Expanded"/"Collapsed"), at least 48 dp high, and tagged [expandTag] for tests.
 *
 * Only secondary detail goes in [details]: a status line, a primary button, the kill switch, Live/Paper, a PIN prompt, a
 * warning or an error stays outside, always in sight.
 */
@Composable
fun Expandable(
    key: String,
    modifier: Modifier = Modifier,
    label: String = "Show details",
    hideLabel: String = "Hide details",
    summary: (@Composable ColumnScope.() -> Unit)? = null,
    details: @Composable ColumnScope.() -> Unit,
) {
    androidx.compose.runtime.key(key) { ExpandableBody(key, modifier, label, hideLabel, summary, details) }
}

@Composable
private fun ExpandableBody(
    key: String,
    modifier: Modifier,
    label: String,
    hideLabel: String,
    summary: (@Composable ColumnScope.() -> Unit)?,
    details: @Composable ColumnScope.() -> Unit,
) {
    val p = LocalPalette.current
    var open by rememberSaveable(key) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        summary?.invoke(this)
        Row(
            Modifier
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button, onClickLabel = if (open) hideLabel else label) { open = !open }
                .semantics { stateDescription = if (open) "Expanded" else "Collapsed" }
                .testTag(expandTag(key)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (open) "$hideLabel ▴" else "$label ▾", style = Type.bodySmall.copy(color = p.ink, fontWeight = FontWeight.SemiBold),
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (open) details()
    }
}
