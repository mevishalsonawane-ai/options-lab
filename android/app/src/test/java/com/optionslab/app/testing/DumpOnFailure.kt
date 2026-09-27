package com.optionslab.app.testing

import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.printToString
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * CI diagnostics: when a Compose test fails, what was on screen (every window's semantics tree, merged) goes
 * to the CI log, so a "not displayed" or "not found" failure shows what was there instead.
 * Declare it with `@get:Rule(order = 100)` so it runs inside the compose rule, while the screen still exists.
 */
class DumpOnFailure(private val compose: ComposeTestRule) : TestWatcher() {
    override fun failed(e: Throwable, description: Description) {
        val tree = runCatching { compose.onAllNodes(isRoot()).printToString(Int.MAX_VALUE) }.getOrElse { "(unreadable: $it)" }
        val text = "SCREEN AT FAILURE ${description.displayName}\n" + tree.take(12_000) + "\n"
        runCatching { java.io.FileOutputStream(java.io.FileDescriptor.err).apply { write(text.toByteArray()); flush() } }
    }
}
