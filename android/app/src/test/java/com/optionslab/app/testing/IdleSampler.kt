package com.optionslab.app.testing

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.ViewRootForTest
import java.util.concurrent.TimeUnit

/**
 * CI diagnostics for Compose waits that never end ("Compose did not get idle after N attempts").
 *
 * [install] (from [TestApp]) does two things for every Robolectric test:
 *  - Espresso's master idling timeout drops from 60 s to [IDLE_TIMEOUT_S]: a wait that cannot end fails in
 *    seconds, not minutes (and the endless re-measuring behind it cannot fill the heap);
 *  - a daemon thread watches Robolectric's main thread; once it has sat in Compose's idling loop for 5 s it
 *    writes, a few times per stall, the main thread's stack and the state of every Compose root (pending
 *    measure/layout, attached, size) to the process's own stderr, which shows in the CI log.
 */
object IdleSampler {
    const val IDLE_TIMEOUT_S = 20L
    @Volatile private var thread: Thread? = null

    fun install() {
        runCatching {
            Class.forName("androidx.test.espresso.IdlingPolicies")
                .getMethod("setMasterPolicyTimeout", Long::class.javaPrimitiveType, TimeUnit::class.java)
                .invoke(null, IDLE_TIMEOUT_S, TimeUnit.SECONDS)
        }
        if (thread != null) return
        synchronized(this) {
            if (thread != null) return
            thread = Thread(::watch, "idle-sampler").apply { isDaemon = true; start() }
        }
    }

    private fun watch() {
        var since = 0L
        var inStall = 0
        var total = 0
        while (true) {
            try { Thread.sleep(1_000) } catch (_: InterruptedException) { return }
            runCatching {
                val main = Looper.getMainLooper()?.thread ?: return@runCatching
                val stack = main.stackTrace
                if (stack.none { it.className.startsWith("androidx.compose.ui.test.RobolectricIdlingStrategy") }) {
                    since = 0L; inStall = 0; return@runCatching
                }
                val now = System.currentTimeMillis()
                if (since == 0L) since = now
                if (now - since >= 5_000 && inStall < 3 && total < 40) {
                    inStall++; total++
                    write(report(stack, now - since))
                }
            }
        }
    }

    private fun report(stack: Array<StackTraceElement>, ms: Long): String = buildString {
        append("IDLE-SAMPLER: Compose not idle for ${ms / 1000} s\n")
        append("  snapshot has pending changes: ${runCatching { Snapshot.current.hasPendingChanges() }.getOrNull()}\n")
        runCatching {
            val global = Class.forName("android.view.WindowManagerGlobal")
            val wmg = global.getMethod("getInstance").invoke(null)
            val views = global.getDeclaredField("mViews").apply { isAccessible = true }.get(wmg) as List<*>
            views.filterIsInstance<View>().forEach { root ->
                append("  window ${root.javaClass.simpleName} ${root.width}x${root.height} attached=${root.isAttachedToWindow} layoutRequested=${root.isLayoutRequested}\n")
                composeRoots(root).forEach { r ->
                    val v = r.view
                    append("    compose root ${v.javaClass.simpleName}@${Integer.toHexString(System.identityHashCode(v))} ${v.width}x${v.height} " +
                        "pendingMeasureOrLayout=${r.hasPendingMeasureOrLayout} layoutRequested=${v.isLayoutRequested} " +
                        "attached=${v.isAttachedToWindow} visibility=${v.visibility} hasWindowFocus=${v.hasWindowFocus()}\n")
                }
            }
        }.onFailure { append("  (windows unreadable: $it)\n") }
        append("  main thread:\n")
        stack.asSequence()
            .filterNot { e -> e.className.startsWith("java.lang.invoke") || e.className.startsWith("jdk.internal") || e.methodName.contains("\$\$robo\$\$") }
            .take(70).forEach { append("    at $it\n") }
    }

    private fun composeRoots(v: View): List<ViewRootForTest> {
        val out = ArrayList<ViewRootForTest>()
        fun walk(x: View) {
            if (x is ViewRootForTest) out += x
            if (x is ViewGroup) for (i in 0 until x.childCount) x.getChildAt(i)?.let(::walk)
        }
        walk(v)
        return out
    }

    private fun write(text: String) {
        runCatching { java.io.FileOutputStream(java.io.FileDescriptor.err).apply { write(text.toByteArray()); flush() } }
    }
}
