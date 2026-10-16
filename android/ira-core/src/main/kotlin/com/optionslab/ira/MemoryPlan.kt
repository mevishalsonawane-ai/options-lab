package com.optionslab.ira

import kotlinx.coroutines.CompletableDeferred

/**
 * What the app gives back when Android asks for memory (memory audit, 14 Oct 2026, item 9; Boss's rule: OPEN POSITIONS AND
 * ORDERS COME FIRST).
 *
 * Android's trim levels ([ComponentCallbacks2], copied here: ira-core has no Android) say how hard it is asking. Each level
 * frees the [Item]s [releases] lists, the biggest first. The [Never] list is what no level ever frees or slows, written down
 * so a test can hold every level to it: the position watch, the open positions' and orders' prices on the stream, their
 * stop / target / trail / lock / square-off / guard checks and their cadence, the order flow and candles of what is held or
 * armed, and the paper book. Everything here is something made again when next needed (the model loads on the next question,
 * the index reopens on the next research question, the diary reads its file again).
 *
 * On Android 14 and later most trims are BACKGROUND (the app's screen is not showing), not pressure: they are counted as
 * "background trims" ([counted]) and still free the listed items. Pure; no Android, no clock.
 */
object MemoryPlan {
    // ComponentCallbacks2's levels.
    const val RUNNING_MODERATE = 5
    const val RUNNING_LOW = 10
    const val RUNNING_CRITICAL = 15
    const val UI_HIDDEN = 20
    const val BACKGROUND = 40
    const val MODERATE = 60
    const val COMPLETE = 80

    /** What a trim may free. None of these is read by the order or tick path. */
    enum class Item(val words: String) {
        MODEL("the language model"),
        // 15 Oct: the Signal tester's Telegram reader (TDLib, read-only) - started again by its next round once the pressure passes.
        TELEGRAM("the Telegram reader"),
        KNOWLEDGE_INDEX("the research index"),
        // 15 Oct: Jarvis's concept book (read again by the next concept question or the Market concepts screen).
        CONCEPTS("the concept book"),
        SHARED_FINDINGS("the shared knowledge's findings counts"),
        ANSWER_CACHE("the short-answer readings"),
        MOVE_SUMMARIES("the big-move summaries"),
        DIARY("the diary's lines in memory"),
        IDLE_CANDLES("the candles of instruments quiet for two hours"),
        IDLE_PATHS("the tick paths of instruments no longer streamed"),
    }

    /** What no trim level ever frees or slows (Boss's rule). */
    enum class Never(val words: String) {
        POSITION_WATCH("the position watch"),
        POSITION_PRICES("the open positions' and orders' prices on the stream"),
        EXIT_CHECKS("the stop, target, trail, lock, square-off and guard checks"),
        WATCH_CADENCE("the cadence of those checks"),
        HELD_FLOW("the order flow of what is held or armed"),
        HELD_CANDLES("the candles of what is held or armed"),
        PAPER_BOOK("the paper book"),
    }

    /**
     * The items [level] frees, biggest first. The screen just hidden frees the model only; the system starting to run low
     * (the app still showing) frees the model and the small caches; everything else - the app in the background, or the
     * system low or critical - frees every item. Unknown levels are read as the nearest one below.
     */
    fun releases(level: Int): List<Item> = when {
        level >= BACKGROUND -> Item.entries
        level >= UI_HIDDEN -> listOf(Item.MODEL)
        level >= RUNNING_LOW -> Item.entries
        level >= RUNNING_MODERATE -> listOf(Item.MODEL, Item.TELEGRAM, Item.ANSWER_CACHE, Item.MOVE_SUMMARIES)
        else -> emptyList()
    }

    /** The health page counts this trim (the screen merely hidden is not one). */
    fun counted(level: Int): Boolean = level >= RUNNING_MODERATE && level != UI_HIDDEN

    /** "background trim" (the app not showing) or "memory trim" (the system running low while it shows). */
    fun kind(level: Int): String = if (level >= BACKGROUND) "background trim" else "memory trim"

    /** Pressure lasts this long after a trim: the shared knowledge's load waits until the close then (in market hours). */
    const val PRESSURE_MS = 5 * 60_000L

    /** A trim at [lastTrimMs] (0: none) still counts as pressure at [nowMs]. */
    fun underPressure(lastTrimMs: Long, nowMs: Long): Boolean = lastTrimMs > 0 && nowMs - lastTrimMs in 0 until PRESSURE_MS

    /**
     * The shared knowledge and the research index load now ([StartOrder.Step.KNOWLEDGE], in the background), or wait until
     * after the close: they wait in market hours (NSE or MCX) while the phone is under memory pressure or in battery saver.
     */
    fun knowledgeNow(marketHours: Boolean, pressure: Boolean, saver: Boolean): Boolean = !marketHours || !(pressure || saver)
}

/**
 * The on-device model and memory (audit item 1): it is the largest single item (the 0.5B model's 491 MB file mapped, plus
 * its working memory), so it leaves memory sooner and is not loaded ahead while trading may need the phone. Pure.
 */
object ModelMemory {
    /** Unused this long, it leaves memory (was 10 minutes). */
    const val IDLE_MS = 3 * 60_000L
    /** ... in NSE's or MCX's hours, or while Jarvis listens (the recognizer needs the memory). */
    const val BUSY_IDLE_MS = 60_000L

    fun idleMs(marketHours: Boolean, listening: Boolean): Long = if (marketHours || listening) BUSY_IDLE_MS else IDLE_MS

    /** Loaded ahead (the Ira page opened): never in NSE's or MCX's hours. */
    fun preload(marketHours: Boolean): Boolean = !marketHours

    /**
     * May a screen-only rewrite load the model from nothing? Not in market hours: the draft shown stands (Jarvis's answer is
     * already complete; the rewrite only rewords it). A model already loaded is used.
     */
    fun coldLoadForRewrite(marketHours: Boolean): Boolean = !marketHours
}

/**
 * The start-up order (Boss, 14 Oct; CLAUDE.md: open positions and orders come first), on every launch and every restart,
 * a low-memory one included:
 *  1. [Step.POSITIONS]: the open positions and their stops restored, and the watch running.
 *  2. [Step.STREAM]: the stream brought back, the arms and the paper book.
 *  3. [Step.KNOWLEDGE]: only then, on a low-priority background thread, the shared knowledge and the research index.
 *
 * [done] marks a step (a step marked before the one ahead of it waits for it: the order of [log] is always the order
 * above); [await] suspends until a step is done. Thread-safe. The clock is the caller's.
 */
class StartOrder {
    enum class Step(val words: String) {
        POSITIONS("open positions and their stops restored and watched"),
        STREAM("the stream, the arms and the paper book"),
        KNOWLEDGE("the shared knowledge and the research index"),
    }

    private val marks = Step.entries.associateWith { CompletableDeferred<Long>() }
    private val ready = HashMap<Step, Long>()
    private val order = ArrayList<Pair<Step, Long>>()

    /**
     * [step] finished at [atMs]. When the steps before it are already done it is logged now; otherwise it is held and logged
     * as soon as they are (so a quick later step can never be counted ahead of the positions). Marking twice: the first counts.
     */
    fun done(step: Step, atMs: Long) {
        val released = ArrayList<Pair<Step, Long>>()
        synchronized(this) {
            if (step in ready || order.any { it.first == step }) return
            ready[step] = atMs
            while (true) {
                val next = Step.entries.getOrNull(order.size) ?: break
                val at = ready.remove(next) ?: break
                val t = maxOf(at, order.lastOrNull()?.second ?: at)
                order += next to t
                released += next to t
            }
        }
        for ((s, t) in released) marks.getValue(s).complete(t)
    }

    /** Suspends until [step] is done (and every step before it); its time. */
    suspend fun await(step: Step): Long = marks.getValue(step).await()

    fun isDone(step: Step): Boolean = marks.getValue(step).isCompleted

    /** The steps done, in order, with their times. */
    fun log(): List<Pair<Step, Long>> = synchronized(this) { ArrayList(order) }

    /** Milliseconds from [startMs] to the positions watched (null: not yet): the release note's start-up figure. */
    fun positionsWatchedAfter(startMs: Long): Long? = synchronized(this) { order.firstOrNull { it.first == Step.POSITIONS }?.second?.minus(startMs) }
}
