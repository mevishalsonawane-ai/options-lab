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
        Alerts.forgetPosted()
        runCatching { Holidays::class.java.getDeclaredField("cache").apply { isAccessible = true }.set(null, null) }
        MainActivity.tabRequests.value = null
        MainActivity.closeRequests.value = null
        SessionLock.lock()
    }

    /** Whether an alert containing [text] was posted since the last reset, shown or already hidden (a running test clock hides a banner at once). */
    /** (Posted since the last reset, shown or already hidden (a running test clock hides a banner at once). */
    fun alerted(text: String) = (Alerts.queue.value + Alerts.posted).any { text in it.text }
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

/**
 * Was: stops the compose clock for screens with text-field dialogs. Those dialogs never settled because the
 * dialog window re-measured at two widths for ever (fixed in the app's AlertDialog); on a paused clock
 * Compose's idling loop has no time limit, so one such wait held a CI job for half an hour. The clock now
 * runs; [frames] and [until] work either way.
 */
fun ComposeTestRule.pause() { /* the clock keeps running */ }

/** A few frames on (only needed with the clock paused). */
fun ComposeTestRule.frames(n: Int = 8) { if (!mainClock.autoAdvance) repeat(n) { mainClock.advanceTimeByFrame() } }

/**
 * Waits for [ok] (real threads: PIN checks, file work). Each round moves the compose clock one frame on and runs
 * the Android main looper: work the app hands to the main thread (the model's viewModelScope, which resumes on
 * Dispatchers.Main after its IO; a Handler post) runs there, and [ComposeTestRule.waitUntil] alone never runs
 * that looper (it only advances the compose clock and sleeps).
 */
fun ComposeTestRule.until(timeoutMs: Long = 20_000, what: String = "the condition", ok: () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMs
    while (!ok()) {
        if (System.currentTimeMillis() > end) throw AssertionError("timed out after $timeoutMs ms waiting for $what")
        mainClock.advanceTimeByFrame()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        Thread.sleep(5)
    }
}

/** Waits until a node with [text] exists in any window. */
fun ComposeTestRule.waitForText(text: String, substring: Boolean = false, timeoutMs: Long = 15_000) =
    until(timeoutMs, "'$text'") { onAllNodesWithText(text, substring = substring, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

fun ComposeTestRule.waitForNoText(text: String, substring: Boolean = false, timeoutMs: Long = 15_000) =
    until(timeoutMs, "no '$text'") { onAllNodesWithText(text, substring = substring, useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }

/**
 * A test still running after [seconds] is interrupted (again every 5 s), so a wait that would never end
 * fails with its stack instead of stalling the whole CI job.
 */
class AreaEWatchdog(private val seconds: Long = 180) : org.junit.rules.TestRule {
    override fun apply(base: org.junit.runners.model.Statement, description: org.junit.runner.Description) =
        object : org.junit.runners.model.Statement() {
            override fun evaluate() {
                val thread = Thread.currentThread()
                val timer = java.util.Timer("watchdog ${description.methodName}", true)
                timer.schedule(object : java.util.TimerTask() { override fun run() { thread.interrupt() } }, seconds * 1000, 5_000)
                try { base.evaluate() } finally { timer.cancel(); Thread.interrupted() }
            }
        }
}

fun ComposeTestRule.has(text: String, substring: Boolean = false) =
    onAllNodesWithText(text, substring = substring, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

/** Brings the lazy page's item holding [text] into composition (a More page is a LazyColumn). */
fun ComposeTestRule.reveal(text: String, substring: Boolean = false) {
    onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToNode(hasText(text, substring = substring))
    frames()
    waitForIdle()
}

/** The switch on the same row as [title] (a ToggleRow's switch is a sibling of its title text). */
fun ComposeTestRule.switchFor(title: String): SemanticsNodeInteraction {
    reveal(title)
    val t = onAllNodesWithText(title, useUnmergedTree = true).fetchSemanticsNodes().first()
    val y = t.boundsInRoot.center.y
    val all = onAllNodes(isToggleable(), useUnmergedTree = true).fetchSemanticsNodes()
    // A row that is itself the switch holds its title; otherwise the switch nearest the title's line.
    val sw: SemanticsNode = all.filter { it.boundsInRoot.top <= y && y <= it.boundsInRoot.bottom }.minByOrNull { it.boundsInRoot.height }
        ?: all.minByOrNull { abs(it.boundsInRoot.center.y - y) } ?: throw AssertionError("no switch near '$title'")
    return onNode(SemanticsMatcher("node ${sw.id}") { it.id == sw.id }, useUnmergedTree = true)
}
