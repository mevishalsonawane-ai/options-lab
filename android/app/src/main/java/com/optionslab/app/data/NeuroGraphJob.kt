package com.optionslab.app.data

import android.content.Context
import com.optionslab.ira.neuro.NeuroGraph
import com.optionslab.ira.neuro.NeuroStore
import com.optionslab.ira.neuro.ResearchHint
import com.optionslab.ira.neuro.ResearchHints
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * The NeuroGraph on this phone: what Jarvis learns from the Dhan market data kept in files/dhan, built into files/neuro
 * ([NeuroStore]; the builder and the statistics are :ira-core's com.optionslab.ira.neuro). It is built by itself after
 * each finished download or data-pack import ([afterData], in [com.optionslab.app.work.NeuroWorker]): an incremental
 * update reads only the new days; a full rebuild (no graph yet, older data arrived, data deleted) waits for the charger
 * unless Boss taps Rebuild graph ([rebuild]). Jarvis reads it through [graphOrNull]; the Strategy Lab can read
 * [researchHints] (proven relations only, for paper research).
 *
 * Learning never arms, trades, goes live or changes any risk: the graph only informs answers and paper research.
 * IraGoldAlgo has none of it.
 */
object NeuroGraphJob {
    data class Progress(val running: Boolean = false, val stage: String = "", val fraction: Float = 0f, val last: String? = null)

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> get() = _progress

    private val running = Mutex()

    /**
     * Run [block] while no graph build runs (waiting for one that is stopping to let go): deleting the Dhan data
     * ([DhanSource.deleteData]) takes this, so a build never reads or writes beside a delete.
     */
    suspend fun <T> whileStopped(block: suspend () -> T): T = running.withLock { block() }

    /** files/neuro, beside the Dhan store; null before the app has started it. */
    private fun dir(): File? = runCatching { File(DhanSource.root().parentFile, "neuro") }.getOrNull()

    private fun store(): NeuroStore? = if (com.optionslab.app.BuildConfig.GOLD) null else dir()?.let { NeuroStore(it) }

    /** Called when a download run or a data-pack import has finished: the graph learns the new days in the background. */
    fun afterData(context: Context) {
        if (com.optionslab.app.BuildConfig.GOLD) return
        com.optionslab.app.work.NeuroWorker.after(context.applicationContext)
    }

    /** Boss tapped Rebuild graph: everything relearned from the store, now. */
    fun rebuild(context: Context) {
        if (com.optionslab.app.BuildConfig.GOLD) return
        com.optionslab.app.work.NeuroWorker.now(context.applicationContext)
    }

    /** Why the next build must be a full one (null: an incremental update is enough). Reads the store's folder list. */
    fun needsFull(): String? {
        val s = store() ?: return null
        val files = DhanSource.filesOrNull() ?: return null
        return com.optionslab.ira.neuro.NeuroBuilder.needsFull(files, s.state())
    }

    /**
     * One build. [full] relearns everything; otherwise only what is new, and a needed full rebuild is done only when
     * [allowFull]. Returns what to show. One build at a time; stops when the coroutine is cancelled (the state stays
     * consistent and the next build resumes).
     */
    suspend fun run(full: Boolean, allowFull: Boolean): String {
        val st = store() ?: return "IraGoldAlgo keeps no NeuroGraph."
        val files = DhanSource.filesOrNull() ?: return "No Dhan data on this phone yet: nothing to learn from."
        if (!running.tryLock()) return "The graph is already being built."
        val ctx = currentCoroutineContext()
        try {
            _progress.value = Progress(true, "Reading the Dhan data", 0f, _progress.value.last)
            val g = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                st.build(files, Market.today(), System.currentTimeMillis(), full, allowFull,
                    progress = { stage, f -> _progress.value = _progress.value.copy(running = true, stage = stage, fraction = f) },
                    active = { ctx.isActive })
            }
            cached = null
            val said = if (g == null) "No graph yet." else "${Market.today()}: ${g.nodes.size} nodes, ${g.edges.size} links, ${g.provenCount} proven"
            _progress.value = Progress(false, "", 1f, said)
            return said
        } catch (e: kotlinx.coroutines.CancellationException) {
            _progress.value = Progress(false, "", 0f, "Stopped; the next build carries on from there")
            throw e
        } catch (_: Exception) {
            val said = "The graph could not be built just now"
            _progress.value = Progress(false, "", 0f, said)
            return said
        } finally {
            running.unlock()
        }
    }

    @Volatile private var cached: Pair<Long, NeuroGraph>? = null

    /** The graph as last built (read once and kept until it changes), or null: none built, or IraGoldAlgo. */
    fun graphOrNull(): NeuroGraph? {
        val st = store() ?: return null
        val stamp = st.builtAt() ?: return null
        cached?.let { (t, g) -> if (t == stamp) return g }
        val g = st.graph() ?: return null
        cached = stamp to g
        return g
    }

    /** The graph's counts for the screen, or null. */
    fun summary(): NeuroGraph.Summary? = graphOrNull()?.summary()

    /** Proven sequences and timings for the Strategy Lab to research ON PAPER; they arm nothing. */
    fun researchHints(limit: Int = 10): List<ResearchHint> = graphOrNull()?.let { ResearchHints.from(it, limit) } ?: emptyList()

    /** Forget the graph (it is rebuilt from the Dhan data on the next build). */
    fun forget(): Boolean { cached = null; return store()?.clear() ?: true }
}
