package com.optionslab.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

/**
 * 20 Nov 2026 (audit: "after a network change the relay stays dead for up to 45 seconds"): Android's default-network callback.
 * When the phone's network really changes ([com.optionslab.ira.NetChange]: Wi-Fi to mobile data or back, or a network lost and
 * back), the price stream is opened again at once on the new network, held positions first ([KiteStream.networkChanged]), and
 * the static-IP relay's SSH session on the old network is let go so the next order connects afresh instead of waiting up to
 * 45 s for its keep-alives to fail ([Relay.networkChanged]).
 *
 * The callback runs on Android's own thread and only records the change; the work runs on this object's background scope.
 * Nothing here reads the settings vault, a file or the network. Registered once per process ([start]); a phone that refuses
 * the callback simply keeps the old behaviour (the stream's watchdog and the relay's keep-alives).
 */
object NetWatch {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + ScopeGuard.handler("NetWatch"))
    private val started = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var state = com.optionslab.ira.NetChange.State()

    /** Network changes seen in this process (the diagnostics and the tests). */
    @Volatile var changes = 0
        private set

    /** B5-D: times the stream and the relay were actually opened again (at most one each [debounceMs]: [available]). */
    @Volatile var acts = 0
        private set

    /**
     * B5-D (a flapping network): the stream and the relay are opened again at most once in this long. A change inside it is acted on
     * once at its end, on the network then current - never a reconnect per flap.
     */
    @Volatile internal var debounceMs = 5_000L

    /** When the last reconnect began (monotonic; [Long.MIN_VALUE]: none yet), and whether one is already waiting to run. */
    private var lastActAt = Long.MIN_VALUE
    private var pending = false
    private val actLock = kotlinx.coroutines.sync.Mutex()
    /** When the latest change was seen, on the stream's monotonic clock ([KiteStream.networkChanged]). */
    private var lastChangeSeen = Long.MAX_VALUE
    private fun mono(): Long = System.nanoTime() / 1_000_000L

    fun start(context: android.content.Context) {
        if (!started.compareAndSet(false, true)) return
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: run { started.set(false); return }
        val ok = runCatching {
            cm.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) { available(network.toString()) }
                override fun onLost(network: android.net.Network) { lost(network.toString()) }
            })
        }.isSuccess
        if (!ok) started.set(false)
    }

    /** [id] is the default network now. */
    @Synchronized
    internal fun available(id: String) {
        val (next, changed) = com.optionslab.ira.NetChange.available(state, id)
        state = next
        if (!changed) return
        changes++
        // B5-D: when the latest change was seen (the stream's monotonic clock): a connection opened after it is kept.
        lastChangeSeen = runCatching { KiteStream.monoNow() }.getOrDefault(Long.MAX_VALUE)
        if (pending) return                                  // one is already waiting: it acts on the network current then
        pending = true
        val last = lastActAt
        val wait = if (last == Long.MIN_VALUE) 0L else (last + debounceMs - mono()).coerceIn(0L, debounceMs)
        scope.launch {
            if (wait > 0) kotlinx.coroutines.delay(wait)
            // (B5 merge review: the stream's part at once; only the relay's part runs one at a time - [act].)
            val seen = synchronized(this@NetWatch) { pending = false; lastActAt = mono(); acts++; lastChangeSeen }
            act(seen)
        }
    }

    @Synchronized
    internal fun lost(id: String) { state = com.optionslab.ira.NetChange.lost(state, id) }

    /**
     * The stream first (a cancel and a kick: memory and the socket only), then the relay (its session let go, then warmed again).
     * B5 merge review: only the relay's part runs one at a time ([actLock]: it can wait for calls in flight and an SSH connect); the
     * stream's never waits behind it.
     */
    private suspend fun act(seenAt: Long) {
        val dropped = runCatching { (streamActForTest ?: KiteStream::networkChanged)(seenAt) }.getOrDefault(false)
        runCatching { Diag.record(com.optionslab.ira.StreamHealth.AREA, "the phone's network changed: " +
            (if (dropped) "the stream is opened again at once on the new network" else "the stream was not connected")) }
        actLock.withLock { runCatching { (relayActForTest ?: { Relay.networkChanged() })() } }
    }

    /** TEST ONLY: the stream's and the relay's parts of [act] in place of the real ones (null: as in the app). */
    @Volatile internal var streamActForTest: ((Long) -> Boolean)? = null
    @Volatile internal var relayActForTest: (() -> Unit)? = null

    /** TEST ONLY: forget the network seen (as a new process). */
    internal fun resetForTest() {
        synchronized(this) { state = com.optionslab.ira.NetChange.State(); changes = 0; acts = 0; lastActAt = Long.MIN_VALUE; pending = false; debounceMs = 5_000L }
        streamActForTest = null; relayActForTest = null
    }
}
