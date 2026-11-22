package com.optionslab.app.data

import kotlinx.coroutines.CoroutineExceptionHandler

/**
 * 22 Nov 2026 (B6 review: "app-lifetime scopes have no CoroutineExceptionHandler"): a failure that escapes a job of one of the app's
 * long-lived background scopes went to the thread's uncaught-exception handler, which in a release build ends the whole process -
 * the position watch with it - with no word of why. Each such scope now carries [handler]: the failure is noted in the diagnostics
 * diary (the scope's name, the exception's class and the first frame of the app's own code: never its message, which can carry an
 * API reply, a token or an amount) and the process goes on. A cancellation is never a failure and never reaches it.
 */
object ScopeGuard {
    /** The diary's area for these lines. */
    const val AREA = "background"

    /** The handler for the scope named [name] (words the app chose, e.g. "price stream"). */
    fun handler(name: String): CoroutineExceptionHandler = CoroutineExceptionHandler { _, e -> caught(name, e) }

    /** How many failures the handlers caught in this process (the tests; memory only). */
    private val count = java.util.concurrent.atomic.AtomicLong()
    val caughtCount: Long get() = count.get()

    /** [e] escaped a job of [name]'s scope: noted (never thrown). */
    internal fun caught(name: String, e: Throwable) {
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
