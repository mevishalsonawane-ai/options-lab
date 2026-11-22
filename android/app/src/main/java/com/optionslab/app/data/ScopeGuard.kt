package com.optionslab.app.data

import kotlinx.coroutines.CoroutineExceptionHandler

/**
 * 22 Nov 2026 (B6 review: "app-lifetime scopes have no CoroutineExceptionHandler"): a failure that escapes a job of one of the app's
 * long-lived background scopes went to the thread's uncaught-exception handler, which in a release build ends the whole process -
 * the position watch with it - with no word of why. Each such scope now carries [handler]: the failure is noted in the diagnostics
 * diary (the scope's name, the exception's class and the first frame of the app's own code: never its message, which can carry an
 * API reply, a token or an amount) and the process goes on - unless it is [fatal] (out of memory, a class that cannot load), which
 * still ends the process as before. A cancellation is never a failure and never reaches it.
 */
object ScopeGuard {
    /** The diary's area for these lines. */
    const val AREA = "background"

    /** The handler for the scope named [name] (words the app chose, e.g. "price stream"). */
    fun handler(name: String): CoroutineExceptionHandler = CoroutineExceptionHandler { _, e -> caught(name, e) }

    /** How many failures the handlers caught in this process (the tests; memory only). */
    private val count = java.util.concurrent.atomic.AtomicLong()
    val caughtCount: Long get() = count.get()

    /**
     * 22 Nov 2026 (second review): failures the process must not outlive - memory gone, the virtual machine broken
     * ([VirtualMachineError]), a class that could not load or initialise ([LinkageError]: [NoClassDefFoundError],
     * [ExceptionInInitializerError]). A half-dead process (a dead stream, a lane that will never run again) is worse than a restart,
     * which restores and watches the open positions first.
     */
    internal fun fatal(e: Throwable): Boolean = e is VirtualMachineError || e is LinkageError

    /**
     * [e] escaped a job of [name]'s scope: noted (never thrown). A [fatal] one is passed on to the thread's own handler, as with no
     * handler at all: the app's crash handler keeps its report and ends the process.
     */
    internal fun caught(name: String, e: Throwable) {
        if (fatal(e)) {
            val t = Thread.currentThread()
            t.uncaughtExceptionHandler.uncaughtException(t, e)
            return
        }
        count.incrementAndGet()
        runCatching { Diag.record(AREA, line(name, e)) }
    }

    /** The diary's words for [e] in [name]'s scope: the class and the app's first frame, never the message. Pure. */
    internal fun line(name: String, e: Throwable): String {
        val at = e.stackTrace.firstOrNull { it.className.startsWith("com.optionslab") }
            ?.let { " at ${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }.orEmpty()
        return "$name: a background job failed (${e.javaClass.simpleName}$at); the app carries on"
    }
}
