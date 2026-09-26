package com.optionslab.app.testing

import android.app.Application
import android.net.Uri
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performScrollToNode
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.optionslab.app.MainActivity
import com.optionslab.app.data.Holidays
import com.optionslab.app.security.SessionLock
import com.optionslab.app.ui.AppModel
import com.optionslab.app.work.Alerts
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.abs

/**
 * Helpers for the navigation, More-page and security tests (area E). Nothing here changes the
 * shared harness: it only clears global state the harness leaves alone and builds test doubles.
 */
object AreaE {
    /**
     * Global state that outlives a test (the app's `object`s) and that [TestApp] does not clear:
     * the alert queue, the holiday book's in-memory copy, pending deep links and the session seal.
     */
    fun resetGlobals() {
        Alerts.queue.value.forEach { Alerts.dismiss(it.id) }
        runCatching { Holidays::class.java.getDeclaredField("cache").apply { isAccessible = true }.set(null, null) }
        MainActivity.tabRequests.value = null
        MainActivity.closeRequests.value = null
        SessionLock.lock()
    }

    /** Whether an alert containing [text] is showing (the banner / the dialog's inline alerts). */
    fun alerted(text: String) = Alerts.queue.value.any { text in it.text }
}

/**
 * A real [AppModel] on a fresh, offline phone (no Zerodha account: nothing it starts reaches the
 * network - [NetworkGuard] would record it), owned by a store the test clears, as
 * `ui/OrderFlowLiveTest` does. For pages that only take the model.
 */
class OfflineModel(app: Application) : AutoCloseable {
    private val store = ViewModelStore()
    val model: AppModel = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[AppModel::class.java]
    override fun close() = store.clear()
}

/**
 * Stands in for the system file pickers: every launch is recorded and answered at once with
 * [answer]. [bytesAt] / [written] give a picked file's content and what the app wrote, through
 * Robolectric's content resolver (content:// URIs no provider serves).
 */
class FakePicker(private val app: Application) : ActivityResultRegistryOwner {
    val launched = mutableListOf<Any?>()
    var answer: (Any?) -> Any? = { null }
    private val outputs = HashMap<Uri, ByteArrayOutputStream>()

    fun bytesAt(name: String, bytes: ByteArray): Uri = Uri.parse("content://areae.test/$name").also {
        shadowOf(app.contentResolver).registerInputStream(it, ByteArrayInputStream(bytes))
    }

    fun writable(name: String): Uri = Uri.parse("content://areae.test/$name").also {
        val out = ByteArrayOutputStream()
        outputs[it] = out
        shadowOf(app.contentResolver).registerOutputStream(it, out)
    }

    fun written(uri: Uri): ByteArray? = outputs[uri]?.toByteArray()

    override val activityResultRegistry: ActivityResultRegistry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            launched += input
            @Suppress("UNCHECKED_CAST")
            dispatchResult(requestCode, answer(input) as O)
        }
    }
}

/** Waits (real threads: PIN checks, file work) until a node with [text] exists in any window. */
fun ComposeTestRule.waitForText(text: String, substring: Boolean = false, timeoutMs: Long = 15_000) =
    waitUntil(timeoutMs) { onAllNodesWithText(text, substring = substring, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

fun ComposeTestRule.waitForNoText(text: String, substring: Boolean = false, timeoutMs: Long = 15_000) =
    waitUntil(timeoutMs) { onAllNodesWithText(text, substring = substring, useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }

fun ComposeTestRule.has(text: String, substring: Boolean = false) =
    onAllNodesWithText(text, substring = substring, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

/** Brings the lazy page's item holding [text] into composition (a More page is a LazyColumn). */
fun ComposeTestRule.reveal(text: String, substring: Boolean = false) {
    onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToNode(hasText(text, substring = substring))
    waitForIdle()
}

/** The switch on the same row as [title] (a ToggleRow's switch is a sibling of its title text). */
fun ComposeTestRule.switchFor(title: String): SemanticsNodeInteraction {
    reveal(title)
    val t = onAllNodesWithText(title, useUnmergedTree = true).fetchSemanticsNodes().first()
    val y = t.boundsInRoot.center.y
    val sw: SemanticsNode = onAllNodes(isToggleable(), useUnmergedTree = true).fetchSemanticsNodes()
        .minByOrNull { abs(it.boundsInRoot.center.y - y) } ?: throw AssertionError("no switch near '$title'")
    return onNode(SemanticsMatcher("node ${sw.id}") { it.id == sw.id }, useUnmergedTree = true)
}
