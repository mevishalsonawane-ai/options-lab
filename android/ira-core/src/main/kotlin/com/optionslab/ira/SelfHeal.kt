package com.optionslab.ira

import java.util.Locale

/**
 * Self-healing (Boss, 10 Oct: "make the workers smart", part 2): a health monitor per dependency the watch leans on - Zerodha's
 * requests (latency, errors, 429s), the price stream's freshness, the static-IP relay's ping, the candle feed, the secure
 * storage's wait, the phone's battery and heat - each with a circuit breaker ([Breaker]: shut after repeated failures, opened
 * again for one trial after a pause that doubles each time it shuts again), the fallback it implies ([Fallbacks]) and incidents
 * told ONCE each, in plain words, when one starts and when it ends ([Monitor.evaluate]).
 *
 * The fallbacks only ever lower risk or keep exits going:
 *  - the stream stale: prices from Zerodha's quotes (REST) - the exits keep their prices;
 *  - Zerodha's requests slow or failing: the stream's prices and the books as last read - the exits keep deciding;
 *  - the candle feed failing: the candles built from the stream (local candles);
 *  - the relay down: NEW live entries refused at the existing entry door (exits always go).
 * Nothing here places, changes or closes an order, and nothing here un-blocks anything Boss blocked. Thread-safe; the clock is
 * given; memory only (the diagnostics keep the incidents).
 */
object SelfHeal {
    enum class Dep(val words: String) {
        KITE_REST("Zerodha's requests"),
        STREAM("the live price stream"),
        RELAY("the static-IP relay"),
        CANDLES("the candle feed"),
        KEYSTORE("the phone's secure storage"),
        PHONE("the phone's battery or temperature"),
    }

    enum class Level { OK, SLOW, DOWN }

    // ---- the thresholds ----------------------------------------------------------------------------------------------------

    /** Zerodha's requests: the last [REST_WINDOW] answers; slow at a median of [REST_SLOW_MS] or more. */
    const val REST_WINDOW = 20
    const val REST_SLOW_MS = 2_000L
    /** ... down at half or more of them failing (after at least [REST_MIN] answers), or [RATE_LIMITS] 429s within a minute. */
    const val REST_MIN = 6
    const val RATE_LIMITS = 3
    /** The stream is stale when its last tick is older than this in market hours. */
    const val STREAM_STALE_MS = 10_000L
    /** The relay: down after this many failed pings in a row. */
    const val RELAY_FAILS = 2
    /** The candle feed: down after this many failed reads in a row. */
    const val CANDLE_FAILS = 3
    /** The secure storage: slow when one read waited this long. */
    const val KEYSTORE_SLOW_MS = 2_000L
    /** The phone: low battery (not charging) at or under this, hot at Android's thermal status SEVERE (3) or more. */
    const val BATTERY_LOW_PCT = 15
    const val THERMAL_SEVERE = 3
    /** An incident is over only after this long all well (no flapping told). */
    const val RECOVER_MS = 30_000L

    // ---- the circuit breaker -----------------------------------------------------------------------------------------------

    /**
     * Shut after [failures] failures in a row: [allow] says no until [openMs] (doubled at each shut in a row, at most
     * [capMs]) has passed, then lets ONE trial through (half-open); a success closes it (and the pause starts again at
     * [openMs]), a failure shuts it again for twice as long. Not thread-safe alone ([Monitor] guards it).
     */
    class Breaker(val failures: Int = 5, val openMs: Long = 10_000L, val capMs: Long = 5 * 60_000L) {
        enum class State { CLOSED, OPEN, HALF_OPEN }

        var state = State.CLOSED; private set
        var inRow = 0; private set
        var trips = 0; private set
        var openUntil = 0L; private set
        private var trial = false

        fun allow(now: Long): Boolean = when (state) {
            State.CLOSED -> true
            State.OPEN -> if (now >= openUntil) { state = State.HALF_OPEN; trial = true; true } else false
            State.HALF_OPEN -> if (trial) false else { trial = true; true }
        }

        fun success() { state = State.CLOSED; inRow = 0; trips = 0; trial = false }

        fun failure(now: Long) {
            inRow++
            if (state == State.HALF_OPEN || inRow >= failures) {
                trips++
                state = State.OPEN
                trial = false
                openUntil = now + Supervisor.backoffMs(trips, openMs, capMs)
            }
        }
    }

    // ---- what to do instead ------------------------------------------------------------------------------------------------

    /** The fallbacks in force now (each one only keeps exits going or refuses new live entries). */
    data class Fallbacks(
        /** Prices from Zerodha's quotes (REST): the stream is stale. */
        val restQuotes: Boolean = false,
        /** The stream's prices and the books as last read: Zerodha's requests are slow or failing. */
        val streamAndCachedBook: Boolean = false,
        /** Candles built from the stream: the candle feed is failing. */
        val localCandles: Boolean = false,
        /** New live entries refused at the entry door: the relay is down. */
        val blockLiveEntries: Boolean = false,
        /** The data itself is unreliable (stream stale AND Zerodha's requests down): every new entry waits. */
        val dataUnreliable: Boolean = false,
    ) {
        fun words(): List<String> = listOfNotNull(
            if (restQuotes) "prices from Zerodha's quotes (the stream is stale)" else null,
            if (streamAndCachedBook) "the stream's prices and the last-read books (Zerodha's requests are slow)" else null,
            if (localCandles) "candles built from the stream (the candle feed is failing)" else null,
            if (blockLiveEntries) "new live entries refused (the relay is down; exits still go)" else null,
            if (dataUnreliable) "new entries paused everywhere (no reliable prices; exits still go)" else null,
        )
    }

    /** One incident: [dep] went [level] at [startedAt]; [recoveredAt] when it was over. [what] is said once each way. */
    data class Incident(val dep: Dep, val level: Level, val startedAt: Long, val what: String, val recoveredAt: Long? = null)

    /** A change [Monitor.evaluate] found: an incident started or ended, with its one plain sentence for Boss. */
    data class Change(val incident: Incident, val started: Boolean, val words: String)

    class Monitor(private val clock: () -> Long = { System.currentTimeMillis() }) {
        private val rest = ArrayDeque<Pair<Boolean, Long>>()        // ok, latency
        private val limits = ArrayDeque<Long>()                     // 429s' times
        private var streamLastTick: Long? = null
        private var streamExpected = false
        private var relayFails = 0
        private var relayOn = false
        private var candleFails = 0
        private var keystoreMs = 0L
        private var keystoreAt = 0L
        private var battery: Int? = null
        private var charging = true
        private var thermal = 0
        private val breakers = Dep.entries.associateWith { Breaker() }
        private val open = LinkedHashMap<Dep, Incident>()
        private val okSince = HashMap<Dep, Long>()
        private val history = ArrayDeque<Incident>()

        /** One Zerodha answer: [ok], its [latencyMs], and whether it was a 429 (too many requests). */
        @Synchronized fun rest(ok: Boolean, latencyMs: Long, rateLimited: Boolean = false, now: Long = clock()) {
            rest.addLast(ok to latencyMs); while (rest.size > REST_WINDOW) rest.removeFirst()
            if (rateLimited) limits.addLast(now)
            while (limits.isNotEmpty() && now - limits.first() > 60_000L) limits.removeFirst()
            val b = breakers.getValue(Dep.KITE_REST)
            if (ok) b.success() else b.failure(now)
        }

        /** The stream's last tick ([lastTickMs], null none) and whether it should be streaming now ([expected]: market hours, logged in). */
        @Synchronized fun stream(lastTickMs: Long?, expected: Boolean) { streamLastTick = lastTickMs; streamExpected = expected }

        /** One relay ping: [ok]. [enabled] false: no relay is used (never an incident). */
        @Synchronized fun relay(ok: Boolean, enabled: Boolean = true, now: Long = clock()) {
            relayOn = enabled
            relayFails = if (ok) 0 else relayFails + 1
            val b = breakers.getValue(Dep.RELAY)
            if (ok) b.success() else b.failure(now)
        }

        /** One candle-feed read: [ok] (it answered with candles). */
        @Synchronized fun candles(ok: Boolean, now: Long = clock()) {
            candleFails = if (ok) 0 else candleFails + 1
            val b = breakers.getValue(Dep.CANDLES)
            if (ok) b.success() else b.failure(now)
        }

        /** The secure storage's last wait. */
        @Synchronized fun keystore(waitMs: Long, now: Long = clock()) { keystoreMs = waitMs; keystoreAt = now }

        /** The phone: battery percent (null unknown), charging, Android's thermal status (0 none .. 6 shutdown). */
        @Synchronized fun phone(batteryPct: Int?, charging: Boolean, thermalStatus: Int) {
            battery = batteryPct; this.charging = charging; thermal = thermalStatus
        }

        /** May a request to [dep] go now (its breaker)? A refused one should take the fallback instead. */
        @Synchronized fun allow(dep: Dep, now: Long = clock()): Boolean = breakers.getValue(dep).allow(now)

        @Synchronized fun breaker(dep: Dep): Breaker.State = breakers.getValue(dep).state

        private fun median(xs: List<Long>): Long = xs.sorted().let { if (it.isEmpty()) 0 else it[it.size / 2] }

        /** Each dependency's level now, with its reason. */
        @Synchronized fun levels(now: Long = clock()): Map<Dep, Pair<Level, String>> {
            val out = LinkedHashMap<Dep, Pair<Level, String>>()
            val fails = rest.count { !it.first }
            val slowMs = median(rest.map { it.second })
            out[Dep.KITE_REST] = when {
                breakers.getValue(Dep.KITE_REST).state != Breaker.State.CLOSED -> Level.DOWN to "Zerodha's requests are failing ($fails of the last ${rest.size})"
                rest.size >= REST_MIN && fails * 2 >= rest.size -> Level.DOWN to "Zerodha's requests are failing ($fails of the last ${rest.size})"
                limits.size >= RATE_LIMITS -> Level.SLOW to "Zerodha says too many requests (${limits.size} in a minute)"
                rest.size >= REST_MIN && slowMs >= REST_SLOW_MS -> Level.SLOW to "Zerodha is slow (half the answers take ${secs(slowMs)} or more)"
                else -> Level.OK to "fine"
            }
            val age = streamLastTick?.let { now - it }
            out[Dep.STREAM] = when {
                !streamExpected -> Level.OK to "not needed now"
                age == null || age > STREAM_STALE_MS -> Level.DOWN to "the live price stream has gone quiet" + (age?.let { " (${secs(it)} since its last price)" } ?: "")
                else -> Level.OK to "fine"
            }
            out[Dep.RELAY] = if (relayOn && relayFails >= RELAY_FAILS) Level.DOWN to "the static-IP relay is not answering ($relayFails pings in a row)" else Level.OK to "fine"
            out[Dep.CANDLES] = if (candleFails >= CANDLE_FAILS) Level.DOWN to "the candle feed is failing ($candleFails reads in a row)" else Level.OK to "fine"
            out[Dep.KEYSTORE] = if (keystoreMs >= KEYSTORE_SLOW_MS && now - keystoreAt <= 5 * 60_000L)
                Level.SLOW to "the phone's secure storage was slow (${secs(keystoreMs)} for one read)" else Level.OK to "fine"
            val b = battery
            out[Dep.PHONE] = when {
                thermal >= THERMAL_SEVERE -> Level.SLOW to "the phone is very hot"
                b != null && b <= BATTERY_LOW_PCT && !charging -> Level.SLOW to "the phone's battery is at $b%"
                else -> Level.OK to "fine"
            }
            return out
        }

        /** The fallbacks the levels imply now. */
        @Synchronized fun fallbacks(now: Long = clock()): Fallbacks {
            val l = levels(now)
            val streamDown = l.getValue(Dep.STREAM).first == Level.DOWN
            val restBad = l.getValue(Dep.KITE_REST).first != Level.OK
            return Fallbacks(
                restQuotes = streamDown,
                streamAndCachedBook = restBad && !streamDown,
                localCandles = l.getValue(Dep.CANDLES).first == Level.DOWN,
                blockLiveEntries = l.getValue(Dep.RELAY).first == Level.DOWN,
                dataUnreliable = streamDown && l.getValue(Dep.KITE_REST).first == Level.DOWN,
            )
        }

        /**
         * The incidents that started or ended since the last call, each told once: a dependency newly not OK opens one; it is
         * over once it has been OK for [RECOVER_MS] (a brief recovery that fails again stays the same incident, told once).
         */
        @Synchronized fun evaluate(now: Long = clock()): List<Change> {
            val out = ArrayList<Change>()
            for ((dep, lv) in levels(now)) {
                val (level, why) = lv
                val cur = open[dep]
                if (level != Level.OK) {
                    okSince.remove(dep)
                    if (cur == null) {
                        val inc = Incident(dep, level, now, why)
                        open[dep] = inc
                        out += Change(inc, true, startWords(dep, why))
                    }
                } else if (cur != null) {
                    val since = okSince.getOrPut(dep) { now }
                    if (now - since >= RECOVER_MS) {
                        val done = cur.copy(recoveredAt = now)
                        open.remove(dep); okSince.remove(dep)
                        history.addLast(done); while (history.size > 50) history.removeFirst()
                        out += Change(done, false, endWords(done))
                    }
                }
            }
            return out
        }

        /** Incidents still open. */
        @Synchronized fun openIncidents(): List<Incident> = open.values.toList()

        /** Incidents over, the newest last (at most 50). */
        @Synchronized fun past(): List<Incident> = history.toList()

        /** TEST / a new day. */
        @Synchronized fun reset() {
            rest.clear(); limits.clear(); streamLastTick = null; streamExpected = false; relayFails = 0; candleFails = 0
            keystoreMs = 0; battery = null; charging = true; thermal = 0; open.clear(); okSince.clear(); history.clear()
            breakers.values.forEach { it.success() }
        }
    }

    /** What the fallback is, in Boss's words, for an incident's first line. */
    fun fallbackWords(dep: Dep): String = when (dep) {
        Dep.KITE_REST -> "I'm using the stream's prices and the last-read positions meanwhile; exits still go."
        Dep.STREAM -> "I'm reading prices from Zerodha's quotes meanwhile; exits still go."
        Dep.RELAY -> "New live entries are paused until it answers; exits still go."
        Dep.CANDLES -> "I'm using candles built from the live stream meanwhile."
        Dep.KEYSTORE -> "Nothing to do; the order path keeps what it needs in memory."
        Dep.PHONE -> "Plug the phone in or let it cool; the watch keeps running."
    }

    fun startWords(dep: Dep, why: String): String = why.replaceFirstChar { it.uppercase() } + ". " + fallbackWords(dep)

    fun endWords(i: Incident): String {
        val mins = ((i.recoveredAt ?: i.startedAt) - i.startedAt) / 60_000L
        return "${i.dep.words.replaceFirstChar { it.uppercase() }} is back to normal" +
            (if (mins >= 1) " after $mins min" else "") + "; everything is as before."
    }

    private fun secs(ms: Long): String = if (ms < 10_000) String.format(Locale.ENGLISH, "%.1f s", ms / 1000.0) else "${ms / 1000} s"

    /** An incident for the diagnostics: "10:31:05-10:33:40 the live price stream: the live price stream has gone quiet". */
    fun line(i: Incident, hhmmss: (Long) -> String): String =
        "${hhmmss(i.startedAt)}${i.recoveredAt?.let { "-" + hhmmss(it) } ?: " (still open)"} ${i.dep.words}: ${i.what}"

    /**
     * Restart policy for a worker (part 2): after [after] failures or time-outs in a row it is restarted (a fresh run, its
     * pause reset), at most [maxPerHour] times an hour - past that it is left backing off and an incident is told.
     */
    data class Restart(val after: Int = 3, val maxPerHour: Int = 6)

    fun shouldRestart(failuresInRow: Int, restartsLastHour: Int, policy: Restart = Restart()): Boolean =
        failuresInRow >= policy.after && failuresInRow % policy.after == 0 && restartsLastHour < policy.maxPerHour
}
