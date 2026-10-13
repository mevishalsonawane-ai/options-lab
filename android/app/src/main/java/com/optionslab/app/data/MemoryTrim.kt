package com.optionslab.app.data

import com.optionslab.ira.MemoryPlan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Android asks for memory back (memory audit, 14 Oct, item 9): what [MemoryPlan.releases] lists for the level is let go here,
 * biggest first, on a background thread - the language model, the research index, the shared knowledge's counts, the
 * answer and big-move caches, the diary's lines in memory, and the tick paths and candles of instruments nothing follows.
 *
 * Never touched, at any level ([MemoryPlan.Never]): the position watch and its loop, the open positions' and orders' prices
 * on the stream ([KiteStream.pruneIdle] keeps everything followed), the stop / target / trail / lock / square-off / guard
 * checks and their cadence, the order flow, the candles of what is held or armed, and the paper book. Nothing here reads
 * the disk, the settings vault or the network on the caller's thread.
 */
object MemoryTrim {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** When Android last asked (a counted trim; 0: not in this process): the knowledge's load waits after one in market hours. */
    @Volatile var lastAt = 0L
        private set

    /** A counted trim in the last few minutes ([MemoryPlan.PRESSURE_MS]). */
    fun underPressure(nowMs: Long = System.currentTimeMillis()): Boolean = MemoryPlan.underPressure(lastAt, nowMs)

    /** What the last trim let go (the diagnostics line), memory only. */
    @Volatile var lastLine: String? = null
        private set

    /** [level]'s items let go in the background (returns at once). [counted]: Android's trim counted on the health page. */
    fun onTrim(level: Int, nowMs: Long = System.currentTimeMillis()) {
        val items = MemoryPlan.releases(level)
        if (MemoryPlan.counted(level)) lastAt = nowMs
        if (items.isEmpty()) return
        scope.launch { release(items, level) }
    }

    /** [items] let go now, each on its own (one failing never stops the rest). Internal: the tests call it directly. */
    internal fun release(items: List<MemoryPlan.Item>, level: Int): List<MemoryPlan.Item> {
        val done = ArrayList<MemoryPlan.Item>()
        var paths = 0; var candles = 0
        for (i in items) runCatching {
            when (i) {
                MemoryPlan.Item.MODEL -> com.optionslab.app.ira.IraModel.releaseForMemory()
                MemoryPlan.Item.KNOWLEDGE_INDEX -> com.optionslab.app.ira.KnowledgeLibrary.drop()
                MemoryPlan.Item.SHARED_FINDINGS -> SharedKnowledgeHost.dropCounts()
                MemoryPlan.Item.ANSWER_CACHE -> com.optionslab.ira.ShortAnswer.clearCache()
                MemoryPlan.Item.MOVE_SUMMARIES -> MoveRecorder.dropCaches()
                MemoryPlan.Item.DIARY -> Diag.trimMemory()
                MemoryPlan.Item.IDLE_PATHS -> { val (p, c) = KiteStream.pruneIdle(); paths += p; candles += c }
                // The same pass as the paths (one look at what is followed).
                MemoryPlan.Item.IDLE_CANDLES -> if (MemoryPlan.Item.IDLE_PATHS !in items) candles += KiteStream.pruneIdle().second
            }
            done += i
        }
        lastLine = "${MemoryPlan.kind(level)} (level $level): let go " + done.joinToString(", ") { it.words } +
            (if (paths + candles > 0) " ($paths paths, $candles instruments' candles)" else "") + "; the position watch untouched"
        return done
    }

    /** TEST ONLY: as a fresh process. */
    internal fun resetForTest() { lastAt = 0L; lastLine = null }
}
