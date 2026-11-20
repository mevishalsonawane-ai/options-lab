package com.optionslab.ira

/**
 * 20 Nov 2026 (audit: "after a network change the relay stays dead for up to 45 seconds"): whether the phone's default network
 * really changed, from Android's network callbacks, so the price stream and the static-IP relay are opened again at once on the
 * new network instead of waiting for their own keep-alives to give up (the relay's 3 x 15 s, the stream's 10 s silence).
 *
 *  - The first network seen (the callback reports the current one when it is registered) is not a change.
 *  - Another network becoming the default (Wi-Fi to mobile data, or back) is a change.
 *  - The same network back after it was lost (Wi-Fi dropped and re-joined) is a change too: its sockets did not survive.
 *  - The same network reported again (its capabilities changed) is not.
 *
 * Pure; the caller keeps the [State].
 */
object NetChange {
    data class State(val current: String? = null, val lost: Boolean = false)

    /** [id] is the default network now: the next state, and whether the stream and the relay must be opened again. */
    fun available(s: State, id: String): Pair<State, Boolean> =
        State(id, false) to (s.current != null && (s.current != id || s.lost))

    /** Network [id] was lost: marked when it is the current one (another network's loss changes nothing). */
    fun lost(s: State, id: String): State = if (id == s.current) s.copy(lost = true) else s
}
