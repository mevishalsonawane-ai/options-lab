package com.optionslab.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type

/**
 * The compact rows' one pattern (Boss, 15 Oct: "each strategy has too much data under explaining the strategy ... when we click
 * on the name, a small dialog box with the information should be shown with an OK button"): a strategy's or card's NAME that
 * opens its explanation - rules, settings, research notes, labels, forward-test bars, long captions - in a small themed dialog
 * with one OK button. The row itself keeps only the name, one status line, the key numbers, its controls and its safety badges.
 *
 * Accessibility: the name is a button described "About <name>", at least 48 dp tall. [lines] are worked out when the row is
 * drawn (memory only); each is one paragraph of the dialog.
 */
@Composable
internal fun ExplainedName(name: String, lines: List<String>, modifier: Modifier = Modifier, style: TextStyle? = null, title: String = name,
                           extra: (@Composable () -> Unit)? = null) {
    val p = LocalPalette.current
    var open by remember { mutableStateOf(false) }
    Box(modifier.heightIn(min = 48.dp).semantics { contentDescription = "About $name" }.clickable(role = Role.Button) { open = true },
        contentAlignment = Alignment.CenterStart) {
        Text(name, style = style ?: Type.body.copy(color = p.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
    }
    if (open) ExplainDialog(title, lines, extra) { open = false }
}

/**
 * The explanation itself: [title], each of [lines] a paragraph (scrolls when long), then [extra] (that one arm's own controls
 * and cards - its size, its paper record - never another arm's), and one OK button.
 */
@Composable
internal fun ExplainDialog(title: String, lines: List<String>, extra: (@Composable () -> Unit)? = null, onClose: () -> Unit) {
    com.optionslab.app.ui.components.AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title, style = Type.title) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                lines.filter { it.isNotBlank() }.forEach { Text(keepNumbersWhole(it), style = Type.bodySmall, modifier = Modifier.padding(bottom = 6.dp)) }
                extra?.invoke()
            }
        },
        confirmButton = { com.optionslab.app.ui.components.TextButton(onClose, Modifier.heightIn(min = 48.dp)) { Text("OK") } },
    )
}

/** A short safety or state badge kept on a compact row (Live / Paper, armed, holding, stopped, waiting for its exit). */
@Composable
internal fun RowBadge(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(text, style = Type.label.copy(color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold),
        modifier = modifier.background(color.copy(alpha = 0.12f), RoundedCornerShape(50)).padding(horizontal = 7.dp, vertical = 2.dp))
}

/**
 * A small "About" button for a row whose title is itself a control (a settings switch row): a tap shows only that row's
 * explanation ([lines]) in its own dialog with one OK button. 48 dp, described "About <title>".
 */
@Composable
internal fun AboutButton(title: String, lines: List<String>) {
    val p = LocalPalette.current
    var open by remember { mutableStateOf(false) }
    Box(Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).semantics { contentDescription = "About $title" }.clickable(role = Role.Button) { open = true },
        contentAlignment = Alignment.Center) {
        Text("ⓘ", style = Type.body.copy(color = p.inkSoft, fontSize = 16.sp))
    }
    if (open) ExplainDialog(title, lines) { open = false }
}
