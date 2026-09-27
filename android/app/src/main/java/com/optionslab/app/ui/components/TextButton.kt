package com.optionslab.app.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * material3's TextButton at least 48 dp high (material3 draws it 40 dp high): the button itself, not only the
 * padding Compose adds around it for touches, is a full-size target. Used for every dialog and inline text
 * action in the app.
 */
@Composable
fun TextButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) =
    androidx.compose.material3.TextButton(onClick, modifier.heightIn(min = 48.dp), enabled = enabled, content = content)
