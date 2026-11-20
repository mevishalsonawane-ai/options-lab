package com.optionslab.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var state = com.optionslab.ira.NetChange.State()

    /** Network changes acted on in this process (the diagnostics and the tests). */
    @Volatile var changes = 0
        private set

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
        scope.launch { act() }
    }

    @Synchronized
    internal fun lost(id: String) { state = com.optionslab.ira.NetChange.lost(state, id) }

    /** The stream first (a cancel and a kick: memory and the socket only), then the relay (its session let go, then warmed again). */
    private fun act() {
        val dropped = runCatching { KiteStream.networkChanged() }.getOrDefault(false)
        runCatching { Diag.record(com.optionslab.ira.StreamHealth.AREA, "the phone's network changed: " +
            (if (dropped) "the stream is opened again at once on the new network" else "the stream was not connected")) }
        runCatching { Relay.networkChanged() }
    }

    /** TEST ONLY: forget the network seen (as a new process). */
    internal fun resetForTest() { synchronized(this) { state = com.optionslab.ira.NetChange.State(); changes = 0 } }
}
