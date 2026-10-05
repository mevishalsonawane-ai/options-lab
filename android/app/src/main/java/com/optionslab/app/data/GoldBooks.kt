package com.optionslab.app.data

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * IraGoldAlgo's four paper books ([GoldPaper], [GoldTrendPaper], [GoldDipPaper], [GoldTasPaper]) read from the vault off
 * the screen's thread (Speed, round 2: the Keystore decryption used to hold up the app's start). Until they are read,
 * every change to a book (a pass, a switch, a reset) waits for them, so an empty book is never written over a saved one;
 * the few places that read a book at once from outside the screen wait for them too ([awaitBlocking]). The screens show
 * the empty book (not armed) for that moment, and [ready] says when the saved ones are in.
 */
object GoldBooks {
    /** Completed when the books of the latest [init] are read (already completed before any init: nothing to wait for). */
    @Volatile private var gate: CompletableDeferred<Unit> = CompletableDeferred<Unit>().apply { complete(Unit) }
    private val _ready = MutableStateFlow(true)
    /** True once the saved books are read. */
    val ready: StateFlow<Boolean> = _ready

    /** Points the four books at their files at once and reads them on a background thread. */
    fun init(context: Context) {
        val c = context.applicationContext
        GoldPaper.init(c); GoldTrendPaper.init(c); GoldDipPaper.init(c); GoldTasPaper.init(c)
        val g = CompletableDeferred<Unit>()
        synchronized(this) { gate = g; _ready.value = false }
        Thread({
            try {
                // A later init (tests start the app again in one process) replaces this read: its result is dropped.
                if (gate === g) GoldPaper.load()
                if (gate === g) GoldTrendPaper.load()
                if (gate === g) GoldDipPaper.load()
                if (gate === g) GoldTasPaper.load()
            } finally {
                synchronized(this) { if (gate === g) _ready.value = true }
                g.complete(Unit)
            }
        }, "gold-books").start()
    }

    /** Waits until the books are read (the latest init's). */
    suspend fun awaitLoaded() {
        while (true) {
            val g = gate
            g.await()
            if (g === gate) return
        }
    }

    /**
     * [awaitLoaded] for code that is not suspending; returns at once once read (the usual case). It may run on the main
     * thread (the gold service's notification, diagnostics), so it waits at most [BLOCK_MS]: false when the books are
     * not read by then (the caller goes on with what there is).
     */
    fun awaitBlocking(): Boolean {
        if (gate.isCompleted && _ready.value) return true
        return kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeoutOrNull(BLOCK_MS) { awaitLoaded() } } != null
    }

    private const val BLOCK_MS = 3_000L
}
