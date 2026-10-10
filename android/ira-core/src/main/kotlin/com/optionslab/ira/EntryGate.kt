package com.optionslab.ira

import com.optionslab.engine.orb.ArmPriority
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The one door every NEW position goes through (Boss, 10 Oct: with the watch's workers running side by side, two of them
 * must never open at once what one at a time would not). One entry at a time, from any worker: inside the door the
 * account-wide checks are asked again at the last moment ([enter]'s `recheck`: the kill switch, the daily loss limit, the
 * day lock, ...), so a switch flipped while an entry was on its way stops it, and an entry that waited behind another sees
 * what that one opened. Exits never come here: they are never queued behind an entry.
 *
 * A wait for the door longer than [maxWaitMs] is a refusal ([BUSY]), never an entry sent late. Nothing here places an order
 * by itself: the caller's own work runs inside.
 */
class EntryGate(private val maxWaitMs: Long = 15_000L) {
    sealed class Outcome<out T> {
        data class Went<T>(val value: T) : Outcome<T>()
        data class Refused(val why: String) : Outcome<Nothing>()
    }

    private val door = Mutex()

    /** Entries let through and refused at the door so far (for the diagnostics). */
    @Volatile var went = 0L
        private set
    @Volatile var refused = 0L
        private set
    /** The last refusal's words (the app's own, never data). */
    @Volatile var lastRefusal: String? = null
        private set

    /** Is an entry going through the door now? */
    val busy: Boolean get() = door.isLocked

    /**
     * [block] (the entry itself) once the door is free and [recheck] (asked inside it) found nothing against it; else
     * [Outcome.Refused] with [recheck]'s words, or [BUSY] when the door stayed shut [maxWaitMs]. [key] names the entry
     * (an instrument) for the diagnostics only.
     */
    suspend fun <T> enter(key: String, recheck: suspend () -> String?, block: suspend () -> T): Outcome<T> {
        withTimeoutOrNull(maxWaitMs) { door.lock() } ?: return refuse(BUSY)
        try {
            val why = try { recheck() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) {
                // A check that cannot be read refuses: an entry never goes on a guess.
                "$UNCHECKED (${e.javaClass.simpleName})"
            }
            if (why != null) return refuse(why)
            went++
            return Outcome.Went(block())
        } finally {
            door.unlock()
        }
    }

    private fun refuse(why: String): Outcome.Refused { refused++; lastRefusal = why; return Outcome.Refused(why) }

    companion object {
        const val BUSY = "another entry is still being sent; not sent"
        const val UNCHECKED = "the account-wide checks could not be read; not sent"
    }
}

/**
 * The one-index-one-side rule ([AutoSide]) made safe for traders deciding at the same moment (Boss, 10 Oct). Each trader
 * publishes its open positions only once its entry is booked, so two traders asking [AutoSide.check] in the same second
 * both saw the index free and both bought. Here the check and a claim on the index are one step ([checkAndHold]): a
 * trader let through holds "an entry being placed" on that index and side, which every OTHER trader's check counts as a
 * position, until its own published positions show the entry booked or [ttlMs] passes (an entry refused or unfilled
 * later). A trader's own claims never count against it (its own book is fresh to it, under its own lock).
 *
 * Only ever refuses an automatic entry; never closes, changes or adds anything. Thread-safe; the clock is given.
 */
class EntryHolds(private val ttlMs: Long = 30_000L) {
    private class Hold(val trader: String, val held: AutoSide.Held, val at: Long, val baseline: Int)

    private val holds = ArrayList<Hold>()

    private fun count(list: List<AutoSide.Held>?, underlying: String, direction: Int): Int =
        list.orEmpty().count { it.underlying.equals(underlying, ignoreCase = true) && it.direction == direction }

    /** Claims gone stale ([ttlMs], a clock set back) or booked (their trader's published positions now show them). */
    private fun prune(now: Long, booked: Map<String, List<AutoSide.Held>>) {
        holds.removeAll { h ->
            now - h.at !in 0..ttlMs || count(booked[h.trader], h.held.underlying, h.held.direction) > h.baseline
        }
    }

    /**
     * Whether [trader]'s automatic entry on [underlying] leaning [direction] may go against [held] (every automatic position
     * it should count: its own fresh ones and the others' published ones) and the other traders' claims; null when it may -
     * and then [trader] holds a claim on the index. [booked]: each trader's published positions now (what retires a claim).
     * [who] names the claim in another trader's refusal ("ORB arms holds a BANKNIFTY entry being placed").
     */
    @Synchronized
    fun checkAndHold(trader: String, who: String, underlying: String, direction: Int, held: List<AutoSide.Held>,
                     booked: Map<String, List<AutoSide.Held>>, now: Long, rank: ArmPriority.Rank = ArmPriority.Rank.OTHER,
                     live: Boolean = false): String? {
        prune(now, booked)
        val others = holds.filter { it.trader != trader }.map { it.held }
        val why = AutoSide.check(underlying, direction, held + others, rank, live)
        if (why == null && direction != 0) {
            holds.removeAll { it.trader == trader && it.held.underlying.equals(underlying, ignoreCase = true) && it.held.direction == direction }
            val claim = AutoSide.Held(who, "a ${underlying.uppercase()} entry being placed", underlying.uppercase(), direction,
                "an entry being placed", rank, live)
            holds += Hold(trader, claim, now, count(booked[trader], underlying, direction))
        }
        return why
    }

    /** [trader]'s claims on [underlying] (all of them when null) dropped: its entry was refused or did not fill. */
    @Synchronized
    fun release(trader: String, underlying: String? = null) {
        holds.removeAll { it.trader == trader && (underlying == null || it.held.underlying.equals(underlying, ignoreCase = true)) }
    }

    /** The claims standing now (after [prune] with [booked]). */
    @Synchronized
    fun standing(now: Long, booked: Map<String, List<AutoSide.Held>> = emptyMap()): List<AutoSide.Held> {
        prune(now, booked)
        return holds.map { it.held }
    }

    @Synchronized
    fun clear() { holds.clear() }
}
