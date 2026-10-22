package com.optionslab.app.data

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CompletableDeferred

/**
 * 22 Oct (the overnight kills: "Android stopped it for using too much CPU or battery in the background ... excessive binder
 * traffic during cached", 02:43 and 02:51): the start-up's heavy work that never trades waits here until the app is really in
 * use. An hourly night job (the strategy forge, the study, GitHub sync) starts the app afresh and ends in about a second; the
 * start's heavy reads went on after it, while Android had already put the app among its cached processes - where it may not
 * run - so Android ended it, the job ran again, and so on all night.
 *
 * Opened by ([open]): the app's screen starting ([onStart]), the market watch starting ([com.optionslab.app.work.Jobs.start]),
 * or a job that needs the work's result now (the forge and the study ask for the shared knowledge:
 * [SharedKnowledgeHost.awaitFirstBuild]). Waiting here today: the shared knowledge's first build. Never anything of the open
 * positions: their start-up steps ([PositionsFirst]: every position book, the watch, the stream, NSE and MCX, the protections
 * with Zerodha's stops), the dead-man check ([com.optionslab.app.work.Heartbeat]) and the 09:14 watch alarm run first at every
 * start exactly as before, and never wait here.
 *
 * Open from the outset everywhere but the app's own start ([hold], from [com.optionslab.app.IraAlgoApp.onCreate]): the unit
 * tests' app is never held. Memory only.
 */
object StartGate : DefaultLifecycleObserver {
    @Volatile private var gate: CompletableDeferred<Unit> = CompletableDeferred(Unit)
    @Volatile private var appContext: Context? = null
    private val uiStarted = java.util.concurrent.atomic.AtomicBoolean(false)

    /** What opened it this process ("the app opened", "the market watch", ...); null while held or never held. */
    @Volatile var openedBy: String? = null
        private set

    /** The app's start, before any heavy work is begun: that work waits for [open]. */
    fun hold(context: Context) {
        appContext = context.applicationContext
        synchronized(this) { if (gate.isCompleted && openedBy == null) gate = CompletableDeferred() }
    }

    val isOpen: Boolean get() = gate.isCompleted

    /** Lets the waiting work run; true when this call opened it. Cheap, any thread. */
    fun open(reason: String): Boolean {
        val opened = synchronized(this) { val g = gate; if (g.isCompleted) false else { openedBy = reason; g.complete(Unit) } }
        if (opened) runCatching { Diag.record("start", "the start-up's heavy work runs now ($reason)") }
        return opened
    }

    /** Suspends until [open] (at once when open, or never held). */
    suspend fun await() { gate.await() }

    /**
     * The app's screen started (the process's lifecycle, main thread): the gate opens, and on the first screen start of this
     * process every alarm is armed again in full (as every start did before 22 Oct; a light start in the background armed
     * only the changed ones), off the main thread.
     */
    override fun onStart(owner: LifecycleOwner) {
        open("the app opened")
        val c = appContext ?: return
        if (uiStarted.compareAndSet(false, true)) fullArm = Thread({
            // After the start's own light arming (never two armings side by side).
            runCatching { startArm?.join(30_000) }
            runCatching { com.optionslab.app.work.Jobs.scheduleAll(c) }
        }, "alarms-full").apply { start() }
    }

    /** The app's start's light arming of the alarms ([com.optionslab.app.IraAlgoApp.onCreate]): the screen's full one waits for it. */
    @Volatile var startArm: Thread? = null

    /** The screen's full arming of the alarms (the tests wait for it). */
    @Volatile internal var fullArm: Thread? = null
        private set

    /** TEST ONLY: held as at the app's start (or open again with [held] false). */
    internal fun resetForTest(held: Boolean = false) {
        synchronized(this) { gate = if (held) CompletableDeferred() else CompletableDeferred(Unit); openedBy = null }
        uiStarted.set(false); appContext = null; fullArm = null; startArm = null
    }
}
